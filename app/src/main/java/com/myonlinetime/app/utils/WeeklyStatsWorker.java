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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

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

        // === ФИКСАЦИЯ ЗАКРЫТЫХ ДНЕЙ ===
        // Делаем снимок последних завершённых суток в посуточный защитный кэш,
        // пока их события ещё доступны в системе. Это закрывает «край» недельного
        // графика на устройствах с коротким хранением событий. Выполняем ДО проверки
        // настроек уведомлений: сохранность данных не должна зависеть от пушей.
        snapshotRecentDays(context);

        SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGeneralEnabled = prefs.getBoolean("notif_general_enabled", true);
        boolean isRecordsEnabled = prefs.getBoolean("notif_records_enabled", true);

        if (!isGeneralEnabled || !isRecordsEnabled) {
            return Result.success();
        }

        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        long startOfToday = cal.getTimeInMillis();
        long startCurrentWeek = startOfToday - (6L * 86400000L);
        long startPrevWeek = startCurrentWeek - (7L * 86400000L);

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

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        String currentUid = account != null ? account.getId() : "guest";
        String cacheKey = "notif_history_array_" + currentUid;

        // === СТАБИЛЬНЫЙ clientId ===
        // Детерминированный id на 7-дневную корзину: одинаков для всех запусков
        // воркера в пределах одной недели и для конкретного аккаунта. Локальная и
        // серверная копии получают один и тот же clientId -> дедуп точный, дубли
        // исключены даже при повторном срабатывании WorkManager.
        long weekBucket = (startOfToday / 86400000L) / 7L;
        final String clientId = "weekly_record_" + currentUid + "*" + weekBucket;

        // Если запись за эту неделю для текущего аккаунта уже обработана — выходим,
        // чтобы не показать второй системный пуш и не записать дубль на сервер.
        String lastBucketKey = "last_weekly_record_clientId*" + currentUid;
        if (clientId.equals(prefs.getString(lastBucketKey, ""))) {
            return Result.success();
        }

        saveToLocalHistory(context, cacheKey, clientId, mainText, actionText);
        sendNotification(context, mainText, actionText);

        if (account != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            saveToServer(context, clientId, mainText, actionText, latch);
            try { latch.await(); } catch (InterruptedException e) {}
        }

        // Помечаем неделю как обработанную.
        prefs.edit().putString(lastBucketKey, clientId).apply();

        return Result.success();
    }

    // Фиксирует последние завершённые сутки в посуточном защитном кэше.
    // getFilteredExactTimes для однодневного окна сам перезапишет слот дня
    // истинным значением (set, не max), пока события дня доступны в системе.
    // Поэтому данные истёкших дней не завышаются, а лишь замораживаются на правде.
    private void snapshotRecentDays(Context context) {
        try {
            // Берём с запасом 8 суток к 7-дневному графику.
            for (int back = 1; back <= 8; back++) {
                Calendar dayCal = Calendar.getInstance();
                dayCal.set(Calendar.HOUR_OF_DAY, 0);
                dayCal.set(Calendar.MINUTE, 0);
                dayCal.set(Calendar.SECOND, 0);
                dayCal.set(Calendar.MILLISECOND, 0);
                dayCal.add(Calendar.DAY_OF_YEAR, -back);
                long dayStart = dayCal.getTimeInMillis();

                // Конец = начало следующих суток (DST-корректно). Ровно граница
                // «однодневного» окна, поэтому снимок пишется в слот именно этого дня.
                dayCal.add(Calendar.DAY_OF_YEAR, 1);
                long dayEnd = dayCal.getTimeInMillis();

                UsageMath.getFilteredExactTimes(context, dayStart, dayEnd);
            }
        } catch (Exception ignored) { }
    }

    private void saveToLocalHistory(Context context, String cacheKey, String clientId, String mainText, String actionText) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            String oldCache = prefs.getString(cacheKey, "[]");
            JSONArray oldArray = new JSONArray(oldCache);

            // Гард от локального дубля: если запись с таким clientId уже лежит — не добавляем.
            if (clientId != null && !clientId.isEmpty()) {
                for (int i = 0; i < oldArray.length(); i++) {
                    if (clientId.equals(oldArray.getJSONObject(i).optString("clientId", ""))) {
                        return;
                    }
                }
            }

            JSONObject newItem = new JSONObject();
            newItem.put("type", "time");
            newItem.put("timestamp", System.currentTimeMillis());
            newItem.put("isRead", false);
            newItem.put("mainText", mainText);
            newItem.put("actionText", actionText);
            if (clientId != null) newItem.put("clientId", clientId);

            JSONArray newArray = new JSONArray();
            newArray.put(newItem);

            for (int i = 0; i < oldArray.length(); i++) {
                newArray.put(oldArray.getJSONObject(i));
            }

            // commit() вместо apply() для надёжности
            prefs.edit().putString(cacheKey, newArray.toString()).commit();

            // LocalBroadcastManager, чтобы экран сразу отреагировал
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("UPDATE_BADGE_BROADCAST"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveToServer(Context context, String clientId, String mainText, String actionText, CountDownLatch latch) {
        String freshToken = null;
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken("603306715003-0ptgu4fqnldcsoon9niprvi772m2ebks.apps.googleusercontent.com")
                    .requestEmail().build();
            GoogleSignInClient client = GoogleSignIn.getClient(context, gso);

            GoogleSignInAccount account = Tasks.await(client.silentSignIn());
            freshToken = account.getIdToken();
        } catch (Exception e) {
            freshToken = null;
        }

        if (freshToken != null) {
            VpsApi.authenticateWithGoogle(context, freshToken, new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String token) {
                    VpsApi.addTimeNotification(token, clientId, mainText, actionText, new VpsApi.Callback() {
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
