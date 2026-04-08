package com.myonlinetime.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
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

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

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

        // === ЖЕЛЕЗОБЕТОННЫЙ ФИКС "8-ГО ВЕДРА" ===
        // Обрезаем время до 00:00:00, чтобы получить ровно 7 суток, а не 8!
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        
        long endCurrent = cal.getTimeInMillis(); // Сегодня 00:00
        
        cal.add(Calendar.DAY_OF_YEAR, -7);
        long startCurrent = cal.getTimeInMillis(); // 7 дней назад 00:00
        
        cal.add(Calendar.DAY_OF_YEAR, -7);
        long startPrev = cal.getTimeInMillis(); // 14 дней назад 00:00

        // ИСПОЛЬЗУЕМ НАШ УМНЫЙ USAGE MATH
        long currentWeekTime = UsageMath.sumMap(UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_DAILY, startCurrent, endCurrent));
        long previousWeekTime = UsageMath.sumMap(UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_DAILY, startPrev, startCurrent));

        if (currentWeekTime == 0 && previousWeekTime == 0) {
            return Result.success();
        }

        long diff = Math.abs(currentWeekTime - previousWeekTime);
        long diffHours = diff / (1000 * 60 * 60);
        long diffMins = (diff / (1000 * 60)) % 60;
        
        String timeStr = context.getString(R.string.notif_time_format, diffHours, diffMins);
        String mainText;

        if (currentWeekTime < previousWeekTime) {
            mainText = context.getString(R.string.notif_time_less, timeStr);
        } else if (currentWeekTime > previousWeekTime) {
            mainText = context.getString(R.string.notif_time_more, timeStr);
        } else {
            mainText = context.getString(R.string.notif_time_equal);
        }

        String actionText = context.getString(R.string.notif_action_click);
        
        saveToHistory(prefs, mainText, actionText, System.currentTimeMillis());

        sendNotification(context, mainText, actionText);

        return Result.success();
    }

    private void saveToHistory(SharedPreferences prefs, String mainText, String actionText, long timestamp) {
        try {
            String historyJson = prefs.getString("notif_history_array", "[]");
            JSONArray array = new JSONArray(historyJson);
            
            JSONObject newNotif = new JSONObject();
            newNotif.put("mainText", mainText);
            newNotif.put("actionText", actionText);
            newNotif.put("timestamp", timestamp);
            newNotif.put("isRead", false);
            
            array.put(newNotif); 
            prefs.edit().putString("notif_history_array", array.toString()).apply();
            
        } catch (Exception e) {
            e.printStackTrace();
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
