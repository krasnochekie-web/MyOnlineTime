package com.myonlinetime.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.tasks.Tasks;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class WeeklyStatsWorker extends Worker {

    private static final String CHANNEL_ID = "weekly_stats_channel";
    private static final int NOTIF_ID = 1001;

    public WeeklyStatsWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGeneralEnabled = prefs.getBoolean("notif_general_enabled", true);
        boolean isRecordsEnabled = prefs.getBoolean("notif_records_enabled", true);

        if (!isGeneralEnabled || !isRecordsEnabled) {
            return Result.success();
        }

        // === ЖЕСТКОЕ ВЫРАВНИВАНИЕ ПО ПОЛУНОЧИ (Идеальная точность) ===
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        
        // Начало текущих суток
        long startOfToday = cal.getTimeInMillis(); 
        
        // Начало 7 суток назад (например, с прошлого Понедельника 00:00)
        long startCurrentWeek = startOfToday - (6L * 86400000L); 
        
        // Начало 14 суток назад
        long startPrevWeek = startCurrentWeek - (7L * 86400000L); 

        // Считаем через сырые логи, теперь корзины не разрезаются!
        Map<String, Long> currentWeekMap = UsageMath.getFilteredExactTimes(context, startCurrentWeek, now);
        Map<String, Long> prevWeekMap = UsageMath.getFilteredExactTimes(context, startPrevWeek, startCurrentWeek);

        long currentWeekTime = UsageMath.sumMap(currentWeekMap);
        long previousWeekTime = UsageMath.sumMap(prevWeekMap);

        if (currentWeekTime == 0 && previousWeekTime == 0) {
            return Result.success();
        }

        long diff = currentWeekTime - previousWeekTime;
        long absDiff = Math.abs(diff);
        
        long diffHours = absDiff / (1000 * 60 * 60);
        long diffMins = (absDiff / (1000 * 60)) % 60;
        
        String timeStr = context.getString(R.string.notif_time_format, diffHours, diffMins);
        String mainText;

        if (diff < 0) {
            mainText = context.getString(R.string.notif_time_less, timeStr);
        } else if (diff > 0) {
            mainText = context.getString(R.string.notif_time_more, timeStr);
        } else {
            mainText = context.getString(R.string.notif_time_equal);
        }

        String actionText = context.getString(R.string.notif_action_click);
        
        // CountDownLatch гарантирует, что Worker не умрет до завершения сетевого запроса
        final CountDownLatch latch = new CountDownLatch(1);

        saveToServer(context, mainText, actionText, latch);
        sendNotification(context, mainText, actionText);

        try { latch.await(); } catch (InterruptedException e) {}

        return Result.success();
    }

    // === СОХРАНЕНИЕ В БАЗУ ДАННЫХ (С ПОЛУЧЕНИЕМ СВЕЖЕГО ТОКЕНА) ===
    private void saveToServer(Context context, String mainText, String actionText, CountDownLatch latch) {
        String freshToken = null;
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken("603306715003-0ptgu4fqnldcsoon9niprvi772m2ebks.apps.googleusercontent.com")
                    .requestEmail().build();
            GoogleSignInClient client = GoogleSignIn.getClient(context, gso);
            
            // Запрашиваем новый токен синхронно, так как мы уже в фоновом потоке Worker'а
            GoogleSignInAccount account = Tasks.await(client.silentSignIn());
            freshToken = account.getIdToken();
        } catch (Exception e) {
            // Фолбэк на кэш, если нет сети
            GoogleSignInAccount cached = GoogleSignIn.getLastSignedInAccount(context);
            if (cached != null) freshToken = cached.getIdToken();
        }

        if (freshToken != null) {
            VpsApi.authenticateWithGoogle(context, freshToken, new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String token) {
                    VpsApi.addTimeNotification(token, mainText, actionText, new VpsApi.Callback() {
                        @Override public void onSuccess(String result) { latch.countDown(); }
                        @Override public void onError(String error) { latch.countDown(); }
                    });
                }
                @Override public void onError(String error) { latch.countDown(); }
            });
        } else {
            latch.countDown();
        }
    }

    private void sendNotification(Context context, String mainText, String actionText) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(context.getString(R.string.notif_channel_desc));
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_tab", "time"); 
        
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        int burgundyColor = ContextCompat.getColor(context, R.color.burgundyRed);
        int grayColor = ContextCompat.getColor(context, R.color.textGrayDynamic); 

        String fullText = mainText + " " + actionText;
        SpannableString spannableString = new SpannableString(fullText);
        
        spannableString.setSpan(new ForegroundColorSpan(grayColor), 0, mainText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableString.setSpan(new ForegroundColorSpan(burgundyColor), mainText.length() + 1, fullText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_time_h) 
                .setContentText(spannableString)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(spannableString)) 
                .setColor(burgundyColor) 
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify(NOTIF_ID, builder.build());
    }
}
