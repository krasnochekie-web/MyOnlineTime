package com.myonlinetime.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        // === ПРОВЕРКА НАСТРОЕК УВЕДОМЛЕНИЙ ===
        SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGeneralEnabled = prefs.getBoolean("notif_general_enabled", true);
        boolean isRecordsEnabled = prefs.getBoolean("notif_records_enabled", true);

        // Если выключены Общие ИЛИ выключены Рекорды — просто прерываем работу (не отправляем пуш)
        if (!isGeneralEnabled || !isRecordsEnabled) {
            return Result.success();
        }
        // =======================================

        // Вычисляем промежутки времени
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        
        cal.add(Calendar.DAY_OF_YEAR, -7);
        long oneWeekAgo = cal.getTimeInMillis();
        
        cal.add(Calendar.DAY_OF_YEAR, -7);
        long twoWeeksAgo = cal.getTimeInMillis();

        // Считаем время за текущую неделю (последние 7 дней) и прошлую (с 14 по 7 день назад)
        long currentWeekTime = calculateTime(context, oneWeekAgo, now);
        long previousWeekTime = calculateTime(context, twoWeeksAgo, oneWeekAgo);

        // Если данных вообще нет (например, только установили) - не спамим
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
        sendNotification(context, mainText, actionText);

        return Result.success();
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

        // Интент для открытия приложения на вкладке "Время"
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_tab", "time"); 
        
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        // Раскрашиваем текст
        int burgundyColor = ContextCompat.getColor(context, R.color.burgundyRed);
        int grayColor = ContextCompat.getColor(context, R.color.textGrayDynamic); 

        String fullText = mainText + actionText;
        SpannableString spannableString = new SpannableString(fullText);
        
        // Серый цвет для основной части
        spannableString.setSpan(new ForegroundColorSpan(grayColor), 0, mainText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Бордовый цвет для призыва к действию
        spannableString.setSpan(new ForegroundColorSpan(burgundyColor), mainText.length(), fullText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_clock)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(spannableString)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(spannableString)) // Чтобы текст не обрезался
                .setColor(burgundyColor) // Это окрасит иконку и название приложения в burgundyRed!
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify(NOTIF_ID, builder.build());
    }

    // Хелпер для подсчета времени без системного мусора
    private long calculateTime(Context context, long start, long end) {
        long total = 0;
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return 0;

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        if (stats == null) return 0;

        PackageManager pm = context.getPackageManager();
        Set<String> userApps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) userApps.add(info.activityInfo.packageName);

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        String launcherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";

        for (UsageStats s : stats) {
            String pkg = s.getPackageName();
            long time = s.getTotalTimeInForeground();
            boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || pkg.equals(launcherPkg);
            if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) {
                total += time;
            }
        }
        return total;
    }
}
