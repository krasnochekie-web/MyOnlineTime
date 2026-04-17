package com.myonlinetime.app.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UsageMath {

    // Оставляем эти переменные, чтобы не сломать другие экраны, 
    // но больше не доверяем статическому кэшу для фильтрации!
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

            long now = System.currentTimeMillis();
            todayExactCache = getFilteredExactTimes(context, todayStartMillis, now);
            yesterdayExactCache = getFilteredExactTimes(context, yesterdayStartMillis, todayStartMillis);
        });
    }

    // =========================================================================
    // ТОЧНАЯ МАТЕМАТИКА (По событиям)
    // =========================================================================
    public static Map<String, Long> getFilteredExactTimes(Context context, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        // Собираем актуальные данные прямо СЕЙЧАС, никакого старого кэша!
        Set<String> currentInstalledApps = getInstalledApps(context);
        String launcherPkg = getDefaultLauncher(context);
        Map<String, Boolean> validityCache = new HashMap<>(); // Быстрый локальный кэш для цикла

        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openTimes = new HashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getPackageName() == null) continue;
            
            // Жесткий trim() спасает от багов Android с дубликатами пакетов
            String pkg = event.getPackageName().trim(); 
            
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                openTimes.put(pkg, event.getTimeStamp());
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED || 
                       event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (openTimes.containsKey(pkg)) {
                    long duration = event.getTimeStamp() - openTimes.get(pkg);
                    
                    Boolean isValid = validityCache.get(pkg);
                    if (isValid == null) {
                        isValid = checkAppValidity(context, pkg, currentInstalledApps, launcherPkg);
                        validityCache.put(pkg, isValid);
                    }

                    if (duration > 0 && isValid) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                    openTimes.remove(pkg);
                }
            }
        }
        
        // ЗАКРЫВАЕМ ВИСЯЩИЕ СЕССИИ (Если ты свернул и сразу удалил приложение для теста)
        long endToUse = Math.min(end, System.currentTimeMillis());
        for (Map.Entry<String, Long> entry : openTimes.entrySet()) {
            String pkg = entry.getKey();
            long duration = endToUse - entry.getValue();
            
            Boolean isValid = validityCache.get(pkg);
            if (isValid == null) {
                isValid = checkAppValidity(context, pkg, currentInstalledApps, launcherPkg);
                validityCache.put(pkg, isValid);
            }

            if (duration > 0 && isValid) {
                Long current = results.get(pkg);
                results.put(pkg, (current == null ? 0L : current) + duration);
            }
        }
        
        return results;
    }

    // =========================================================================
    // АГРЕГИРОВАННАЯ МАТЕМАТИКА (Месяцы и годы)
    // =========================================================================
    public static Map<String, Long> getFilteredStats(Context context, int interval, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        Set<String> currentInstalledApps = getInstalledApps(context);
        String launcherPkg = getDefaultLauncher(context);
        Map<String, Boolean> validityCache = new HashMap<>();

        List<UsageStats> stats = usm.queryUsageStats(interval, start, end);
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getPackageName() == null) continue;
                long time = s.getTotalTimeInForeground();
                String pkg = s.getPackageName().trim();
                
                Boolean isValid = validityCache.get(pkg);
                if (isValid == null) {
                    isValid = checkAppValidity(context, pkg, currentInstalledApps, launcherPkg);
                    validityCache.put(pkg, isValid);
                }

                if (time > 0 && isValid) {
                    Long current = results.get(pkg);
                    long currentVal = (current == null) ? 0L : current;
                    // БЕЗУСЛОВНОЕ СУММИРОВАНИЕ! Идеально склеивает старое время и время после переустановки
                    results.put(pkg, currentVal + time);
                }
            }
        }
        return results;
    }

    public static long sumMap(Map<String, Long> map) {
        long total = 0;
        if (map != null) {
            for (long time : map.values()) {
                total += time;
            }
        }
        return total;
    }

    // =========================================================================
    // УМНАЯ СИСТЕМА ПРОВЕРКИ "ПРИЗРАКОВ" И МУСОРА
    // =========================================================================
    private static Set<String> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) {
            apps.add(info.activityInfo.packageName.trim());
        }
        return apps;
    }

    private static String getDefaultLauncher(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName.trim() : "";
    }

    private static boolean checkAppValidity(Context context, String pkg, Set<String> installedApps, String launcherPkg) {
        boolean isSystemTrash = pkg.equals("android") || 
                                pkg.equals("com.android.systemui") || 
                                pkg.equals("com.google.android.gms") || 
                                pkg.equals("com.android.settings") || 
                                pkg.equals(launcherPkg);
        
        if (isSystemTrash) return false;

        // Если оно есть в текущем меню (живое) — берем!
        if (installedApps.contains(pkg)) return true;

        // Гениальная проверка: если его нет в меню, это либо фоновый мусор, либо удаленное тобой приложение
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            // Пакет установлен, но иконки нет -> это скрытый системный процесс. В мусорку.
            return false; 
        } catch (PackageManager.NameNotFoundException e) {
            // ОШИБКА! Пакет не установлен! 
            // Раз Android зафиксировал для него экранное время, значит ты его удалил. Берем!
            return true; 
        }
    }
}
