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

    // === ГЛОБАЛЬНЫЙ КЭШ ===
    // Храним готовые посчитанные мапы, чтобы фрагменты получали их за 0 миллисекунд
    public static Map<String, Long> todayExactCache = null;
    public static Map<String, Long> yesterdayExactCache = null;
    
    // Кэшированные границы времени, чтобы все экраны мерили от одной точки
    public static long todayStartMillis = 0;
    public static long yesterdayStartMillis = 0;

    private static Set<String> cachedUserApps = null;
    private static String cachedLauncherPkg = null;

    // =========================================================================
    // ФОНОВАЯ ПРЕДЗАГРУЗКА (Вызывать из MainActivity)
    // =========================================================================
    public static void preloadCoreStats(final Context context) {
        Utils.backgroundExecutor.execute(() -> {
            // 1. Фиксируем ЕДИНЫЕ границы времени для всего приложения
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            todayStartMillis = cal.getTimeInMillis();

            Calendar yCal = (Calendar) cal.clone();
            yCal.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStartMillis = yCal.getTimeInMillis();

            // 2. Предзагружаем списки приложений
            initAppFilters(context);

            // 3. Считаем ТОЧНОЕ время за Сегодня и Вчера (самое тяжелое)
            long now = System.currentTimeMillis();
            todayExactCache = getFilteredExactTimes(context, todayStartMillis, now);
            yesterdayExactCache = getFilteredExactTimes(context, yesterdayStartMillis, todayStartMillis);
        });
    }

    // =========================================================================
    // МАТЕМАТИКА (Точная по событиям - для дней и недель)
    // =========================================================================
    public static Map<String, Long> getFilteredExactTimes(Context context, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        initAppFilters(context); // На всякий случай проверяем кэш фильтров

        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openTimes = new HashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();
            
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                openTimes.put(pkg, event.getTimeStamp());
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                if (openTimes.containsKey(pkg)) {
                    long duration = event.getTimeStamp() - openTimes.get(pkg);
                    // ПЕРЕДАЕМ CONTEXT ДЛЯ УМНОЙ ПРОВЕРКИ
                    if (duration > 0 && isValidApp(context, pkg)) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                    openTimes.remove(pkg);
                }
            }
        }
        return results;
    }

    // =========================================================================
    // МАТЕМАТИКА (Агрегированная - для месяцев и лет, так как Events там взорвет память)
    // =========================================================================
    public static Map<String, Long> getFilteredStats(Context context, int interval, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        initAppFilters(context);

        List<UsageStats> stats = usm.queryUsageStats(interval, start, end);
        if (stats != null) {
            for (UsageStats s : stats) {
                long time = s.getTotalTimeInForeground();
                String pkg = s.getPackageName();
                
                // ПЕРЕДАЕМ CONTEXT ДЛЯ УМНОЙ ПРОВЕРКИ
                if (time > 0 && isValidApp(context, pkg)) {
                    Long current = results.get(pkg);
                    long currentVal = (current == null) ? 0L : current;

                    if (interval == UsageStatsManager.INTERVAL_YEARLY) {
                        results.put(pkg, Math.max(currentVal, time));
                    } else {
                        results.put(pkg, currentVal + time);
                    }
                }
            }
        }
        return results;
    }

    // Удобный метод для быстрого подсчета суммы из готовой мапы
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
    // ЕДИНЫЙ ФИЛЬТР МУСОРА (С поддержкой удаленных приложений!)
    // =========================================================================
    private static void initAppFilters(Context context) {
        if (cachedUserApps != null && cachedLauncherPkg != null) return;
        
        PackageManager pm = context.getPackageManager();
        cachedUserApps = new HashSet<>();
        
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) {
            cachedUserApps.add(info.activityInfo.packageName);
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        cachedLauncherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
    }

    private static boolean isValidApp(Context context, String pkg) {
        if (pkg == null) return false;
        
        boolean isSystemTrash = pkg.equals("android") || 
                                pkg.equals("com.android.systemui") || 
                                pkg.equals("com.google.android.gms") || 
                                pkg.equals("com.android.settings") || 
                                pkg.equals(cachedLauncherPkg);
        
        if (isSystemTrash) return false;

        // 1. Нормальное установленное приложение (есть в меню)
        if (cachedUserApps.contains(pkg)) return true;

        // 2. Если его нет в меню, проверим: оно вообще существует в системе?
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            // Пакет установлен, но в меню его нет -> это фоновый процесс/служба. Мусор.
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            // ОШИБКА: Пакет не установлен!
            // Раз Android зафиксировал для него экранное время, значит оно было удалено.
            // Пропускаем "призрака" в статистику, чтобы время сходилось!
            return true;
        }
    }
}
