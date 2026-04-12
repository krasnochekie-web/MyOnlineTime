package com.myonlinetime.app.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsageMath {

    // =========================================================================
    // ГЛОБАЛЬНЫЙ КЭШ ПРИЛОЖЕНИЯ (Единый источник истины)
    // =========================================================================
    public static class AppStatsResult {
        public List<String> list;
        public Map<String, Long> times;
        public long totalMillis;
        
        public AppStatsResult(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }

    // 1. Кэш для списков "Время" (0-Сегодня, 1-Вчера, 2-Неделя, 3-Месяц, 4-Год, 5-За всё время)
    public static final ConcurrentHashMap<Integer, AppStatsResult> globalTimeCache = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Boolean> isCalculating = new ConcurrentHashMap<>();

    // 2. НОВОЕ: Кэш для вкладки "Графики" (Массив из 7 дней, индекс 6 - Сегодня)
    public static AppStatsResult[] globalChartCache = new AppStatsResult[7];
    public static boolean isChartCalculating = false; 
    public static boolean isChartReady = false;

    // Свой собственный пул для ядерного прогрева
    private static final ExecutorService preloaderPool = Executors.newFixedThreadPool(5);

    // Старые кэши (Оставляем для обратной совместимости других экранов, если они есть)
    public static Map<String, Long> todayExactCache = null;
    public static Map<String, Long> yesterdayExactCache = null;
    
    public static long todayStartMillis = 0;
    public static long yesterdayStartMillis = 0;

    private static Set<String> cachedUserApps = null;
    private static String cachedLauncherPkg = null;

    // =========================================================================
    // АГРЕССИВНЫЙ ПРОГРЕВ ВСЕГО ПРИЛОЖЕНИЯ (ВЫЗЫВАЕМ ИЗ MAIN ACTIVITY)
    // =========================================================================
    public static void preloadAbsoluteEverything(Context context) {
        // Устанавливаем границы времени (если еще не стоят)
        if (todayStartMillis == 0) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            todayStartMillis = cal.getTimeInMillis();

            Calendar yCal = (Calendar) cal.clone();
            yCal.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStartMillis = yCal.getTimeInMillis();
        }

        // 1. Кидаем 6 параллельных задач для вкладки "Время" (от "Сегодня" до "За всё время")
        for (int i = 0; i <= 5; i++) {
            final int pos = i;
            preloaderPool.execute(() -> computeTimeTabGlobal(context, pos));
        }

        // 2. Кидаем задачу для вкладки "Графики"
        preloaderPool.execute(() -> computeChartGlobal(context));
    }

    // =========================================================================
    // ВНУТРЕННИЙ ДВИЖОК РАСЧЕТОВ (Изолирован от UI)
    // =========================================================================
    private static void computeTimeTabGlobal(Context context, int position) {
        if (globalTimeCache.containsKey(position)) return; 
        isCalculating.put(position, true); 

        long endTime = System.currentTimeMillis();
        Map<String, Long> exactTimes = null;
        Calendar cal = Calendar.getInstance(); 
        
        switch (position) {
            case 0: 
                exactTimes = getFilteredExactTimes(context, todayStartMillis, endTime); 
                todayExactCache = exactTimes; // Обновляем старый кэш заодно
                break;
            case 1: 
                exactTimes = getFilteredExactTimes(context, yesterdayStartMillis, todayStartMillis); 
                yesterdayExactCache = exactTimes; // Обновляем старый кэш заодно
                break;
            case 2: 
                cal.add(Calendar.DAY_OF_YEAR, -7); 
                exactTimes = getFilteredStats(context, UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), endTime); 
                break;
            case 3: 
                cal.add(Calendar.MONTH, -1); 
                exactTimes = getFilteredStats(context, UsageStatsManager.INTERVAL_WEEKLY, cal.getTimeInMillis(), endTime); 
                break;
            case 4: 
                cal.add(Calendar.YEAR, -1); 
                exactTimes = getFilteredStats(context, UsageStatsManager.INTERVAL_YEARLY, cal.getTimeInMillis(), endTime); 
                break;
            case 5: 
                // ЗА ВСЁ ВРЕМЯ (от 2010 года до текущего момента)
                exactTimes = getFilteredStats(context, UsageStatsManager.INTERVAL_YEARLY, 1262304000000L, endTime); 
                break; 
        }

        final Map<String, Long> finalExactTimes = exactTimes;
        final List<String> finalList = new ArrayList<>(finalExactTimes.keySet());
        
        Collections.sort(finalList, (left, right) -> Long.compare(finalExactTimes.get(right), finalExactTimes.get(left)));
        final long finalTotalMillis = sumMap(finalExactTimes);
        
        globalTimeCache.put(position, new AppStatsResult(finalList, finalExactTimes, finalTotalMillis));
        isCalculating.put(position, false); 
    }

    // =========================================================================
    // ВЫЧИСЛИТЕЛЬ ДЛЯ ГРАФИКОВ (Разбивка по 7 дням)
    // =========================================================================
    private static void computeChartGlobal(Context context) {
        if (isChartReady || isChartCalculating) return;
        isChartCalculating = true;

        for (int i = 0; i < 7; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -(6 - i)); 
            
            Map<String, Long> times;

            // Используем мгновенный кэш для Сегодня и Вчера, если они уже готовы
            if (i == 6 && todayExactCache != null) {
                times = todayExactCache;
            } else if (i == 5 && yesterdayExactCache != null) {
                times = yesterdayExactCache;
            } else {
                Calendar startCal = (Calendar) cal.clone();
                startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0); startCal.set(Calendar.SECOND, 0); startCal.set(Calendar.MILLISECOND, 0);
                
                Calendar endCal = (Calendar) cal.clone();
                endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59); endCal.set(Calendar.SECOND, 59); endCal.set(Calendar.MILLISECOND, 999);
                
                times = getFilteredExactTimes(context, startCal.getTimeInMillis(), endCal.getTimeInMillis());
            }

            final Map<String, Long> finalExactTimes = times;
            final List<String> finalList = new ArrayList<>(finalExactTimes.keySet());
            Collections.sort(finalList, (left, right) -> Long.compare(finalExactTimes.get(right), finalExactTimes.get(left)));
            final long finalTotalMillis = sumMap(finalExactTimes);

            globalChartCache[i] = new AppStatsResult(finalList, finalExactTimes, finalTotalMillis);
        }

        isChartReady = true;
        isChartCalculating = false;
    }

    // =========================================================================
    // БАЗОВАЯ МАТЕМАТИКА (События и Агрегация)
    // =========================================================================
    public static Map<String, Long> getFilteredExactTimes(Context context, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        initAppFilters(context); 

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
                    if (duration > 0 && isValidApp(pkg)) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                    openTimes.remove(pkg);
                }
            }
        }
        return results;
    }

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
                
                if (time > 0 && isValidApp(pkg)) {
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
    // ФИЛЬТРЫ МУСОРА
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

    private static boolean isValidApp(String pkg) {
        if (pkg == null) return false;
        boolean isSystemTrash = pkg.equals("android") || 
                                pkg.equals("com.android.systemui") || 
                                pkg.equals("com.google.android.gms") || 
                                pkg.equals("com.android.settings") || 
                                pkg.equals(cachedLauncherPkg);
        
        return cachedUserApps.contains(pkg) && !isSystemTrash;
    }
                }
