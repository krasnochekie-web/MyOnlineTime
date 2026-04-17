package com.myonlinetime.app.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class UsageMath {

    private static final String PREF_SAFE_CACHE = "UsageSafeCache";
    
    public static Map<String, Long> todayExactCache = null;
    public static Map<String, Long> yesterdayExactCache = null;
    
    public static long todayStartMillis = 0;
    public static long yesterdayStartMillis = 0;

    public static void preloadCoreStats(final Context context) {
        Utils.backgroundExecutor.execute(() -> {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            todayStartMillis = cal.getTimeInMillis();

            Calendar yCal = (Calendar) cal.clone();
            yCal.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStartMillis = yCal.getTimeInMillis();

            // Считаем и СРАЗУ сохраняем в сейф, чтобы не потерять быстрые тесты
            todayExactCache = getFilteredExactTimes(context, todayStartMillis, System.currentTimeMillis());
            saveToSafeCache(context, todayStartMillis, todayExactCache);

            yesterdayExactCache = getFilteredExactTimes(context, yesterdayStartMillis, todayStartMillis);
            saveToSafeCache(context, yesterdayStartMillis, yesterdayExactCache);
        });
    }

    // =========================================================================
    // ТОЧНАЯ МАТЕМАТИКА (Сегодня / Вчера)
    // =========================================================================
    public static Map<String, Long> getFilteredExactTimes(Context context, long start, long end) {
        Map<String, Long> systemData = fetchFromAndroidSystem(context, start, end);
        Map<String, Long> safeData = loadFromSafeCache(context, start);
        
        for (Map.Entry<String, Long> entry : safeData.entrySet()) {
            String pkg = entry.getKey();
            long safeTime = entry.getValue();
            long sysTime = systemData.containsKey(pkg) ? systemData.get(pkg) : 0L;
            
            systemData.put(pkg, Math.max(safeTime, sysTime));
        }
        
        saveToSafeCache(context, start, systemData);
        return systemData;
    }

    private static Map<String, Long> fetchFromAndroidSystem(Context context, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        Set<String> currentInstalledApps = getInstalledApps(context);
        String launcherPkg = getDefaultLauncher(context);

        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openTimes = new HashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getPackageName() == null) continue;
            
            String pkg = event.getPackageName().replaceAll("\\s+", ""); 
            
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                openTimes.put(pkg, event.getTimeStamp());
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED || 
                       event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (openTimes.containsKey(pkg)) {
                    long duration = event.getTimeStamp() - openTimes.get(pkg);
                    if (duration > 0 && isValidApp(context, pkg, currentInstalledApps, launcherPkg)) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                    openTimes.remove(pkg);
                }
            }
        }
        
        long endToUse = Math.min(end, System.currentTimeMillis());
        for (Map.Entry<String, Long> entry : openTimes.entrySet()) {
            String pkg = entry.getKey();
            long duration = endToUse - entry.getValue();
            if (duration > 0 && isValidApp(context, pkg, currentInstalledApps, launcherPkg)) {
                Long current = results.get(pkg);
                results.put(pkg, (current == null ? 0L : current) + duration);
            }
        }
        return results;
    }

    // =========================================================================
    // АГРЕГИРОВАННАЯ МАТЕМАТИКА (Неделя / Месяц / Год + Нижние карточки)
    // =========================================================================
    public static Map<String, Long> getFilteredStats(Context context, int interval, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        Set<String> currentInstalledApps = getInstalledApps(context);
        String launcherPkg = getDefaultLauncher(context);

        // 1. Берем данные от системы (без удаленных приложений)
        List<UsageStats> stats = usm.queryUsageStats(interval, start, end);
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getPackageName() == null) continue;
                long time = s.getTotalTimeInForeground();
                String pkg = s.getPackageName().replaceAll("\\s+", "");
                
                if (time > 0 && isValidApp(context, pkg, currentInstalledApps, launcherPkg)) {
                    Long current = results.get(pkg);
                    results.put(pkg, (current == null ? 0L : current) + time);
                }
            }
        }
        
        // 2. ВОТ ОНО! Достаем потерянные данные из нашего Сейфа за весь период
        Map<String, Long> safeData = getSafeDataForInterval(context, start, end);
        for (Map.Entry<String, Long> entry : safeData.entrySet()) {
            String pkg = entry.getKey();
            long safeTime = entry.getValue();
            long sysTime = results.containsKey(pkg) ? results.get(pkg) : 0L;
            
            // Если система удалила данные (sysTime = 0), мы берем спасенные данные (safeTime).
            // Идеально работает для карточек Неделя/Месяц/Год!
            results.put(pkg, Math.max(safeTime, sysTime));
        }
        
        return results;
    }

    // =========================================================================
    // ЛОГИКА "СЕЙФА" (Независимое хранилище времени)
    // =========================================================================
    
    private static String getDayKey(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
        return "day_" + sdf.format(new Date(timestamp));
    }

    private static void saveToSafeCache(Context context, long dayStart, Map<String, Long> data) {
        if (data == null || data.isEmpty()) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SAFE_CACHE, Context.MODE_PRIVATE);
            String key = getDayKey(dayStart);
            
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Long> entry : data.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            sp.edit().putString(key, json.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static Map<String, Long> loadFromSafeCache(Context context, long dayStart) {
        Map<String, Long> map = new HashMap<>();
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SAFE_CACHE, Context.MODE_PRIVATE);
            String key = getDayKey(dayStart);
            String jsonStr = sp.getString(key, "{}");
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, json.getLong(k));
            }
        } catch (Exception ignored) {}
        return map;
    }

    // Собирает математическую сумму из Сейфа за каждый день указанного периода
    private static Map<String, Long> getSafeDataForInterval(Context context, long start, long end) {
        Map<String, Long> aggregatedSafe = new HashMap<>();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        
        long endLimit = end;
        while (cal.getTimeInMillis() <= endLimit) {
            Map<String, Long> dayData = loadFromSafeCache(context, cal.getTimeInMillis());
            for (Map.Entry<String, Long> entry : dayData.entrySet()) {
                String pkg = entry.getKey();
                long current = aggregatedSafe.containsKey(pkg) ? aggregatedSafe.get(pkg) : 0L;
                aggregatedSafe.put(pkg, current + entry.getValue());
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return aggregatedSafe;
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =========================================================================

    public static long sumMap(Map<String, Long> map) {
        long total = 0;
        if (map != null) {
            for (long time : map.values()) {
                total += time;
            }
        }
        return total;
    }

    private static Set<String> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) {
            apps.add(info.activityInfo.packageName.replaceAll("\\s+", ""));
        }
        return apps;
    }

    private static String getDefaultLauncher(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName.replaceAll("\\s+", "") : "";
    }

    private static boolean isValidApp(Context context, String pkg, Set<String> installedApps, String launcherPkg) {
        if (pkg == null) return false;
        
        boolean isSystemTrash = pkg.equals("android") || 
                                pkg.equals("com.android.systemui") || 
                                pkg.equals("com.google.android.gms") || 
                                pkg.equals("com.android.settings") || 
                                pkg.equals(launcherPkg);
        
        if (isSystemTrash) return false;
        if (installedApps.contains(pkg)) return true;

        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return false; 
        } catch (PackageManager.NameNotFoundException e) {
            return true; 
        }
    }
}
