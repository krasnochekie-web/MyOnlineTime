package com.myonlinetime.app.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
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

    // === Короткоживущий кэш дорогих IPC-запросов к PackageManager ===
    private static final long APP_LIST_TTL_MS = 15_000L;
    private static volatile Set<String> cachedInstalledApps = null;
    private static volatile String cachedLauncherPkg = null;
    private static volatile long appListCacheTime = 0L;

    // Запас «до окна» для событий: ловим сессию, начавшуюся до start
    // (например, приложение, открытое через полночь), чтобы обрезать её до start.
    private static final long EVENT_LOOKBACK_MS = 12L * 60L * 60L * 1000L;

    // === Актуализация границ суток ===
    public static synchronized void refreshDayBoundariesIfNeeded() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long realTodayStart = cal.getTimeInMillis();

        if (realTodayStart != todayStartMillis) {
            todayStartMillis = realTodayStart;
            Calendar yCal = (Calendar) cal.clone();
            yCal.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStartMillis = yCal.getTimeInMillis();
            todayExactCache = null;
            yesterdayExactCache = null;
        }
    }

    public static void preloadCoreStats(final Context context) {
        Utils.backgroundExecutor.execute(() -> {
            refreshDayBoundariesIfNeeded();

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            todayStartMillis = cal.getTimeInMillis();

            Calendar yCal = (Calendar) cal.clone();
            yCal.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStartMillis = yCal.getTimeInMillis();

            todayExactCache = getFilteredExactTimes(context, todayStartMillis, System.currentTimeMillis());
            yesterdayExactCache = getFilteredExactTimes(context, yesterdayStartMillis, todayStartMillis);
        });
    }

    public static Map<String, Long> getFilteredExactTimes(Context context, long start, long end) {
        Set<String> currentInstalledApps = getInstalledAppsCached(context);
        String launcherPkg = getDefaultLauncherCached(context);

        // Один кэш валидности пакетов на весь вызов — убирает повторные IPC.
        Map<String, Boolean> validityCache = new HashMap<>();

        Map<String, Long> systemData = fetchFromAndroidSystem(context, start, end, currentInstalledApps, launcherPkg, validityCache);

        // === КЛЮЧЕВАЯ ЗАЩИТА ОТ ИЗМЕНЕНИЯ ИСТЁКШИХ ДНЕЙ ===
        // Защитный кэш на диске хранит данные ПОСУТОЧНО (ключ = дата начала окна).
        // Поэтому его допустимо читать/писать ТОЛЬКО для однодневного окна.
        if (!isSingleDayWindow(start, end)) {
            return systemData;
        }

        if (!systemData.isEmpty()) {
            // Данные за этот день ещё доступны в системе → система = источник истины.
            // Перезаписываем слот дня (set, НЕ max), чтобы истёкший день
            // «замораживался» на правде и не рос со временем.
            saveToSafeCache(context, start, systemData);
            return systemData;
        }

        // Данные за этот день уже вытеснены системой — отдаём то, что сохранили раньше.
        Map<String, Long> restored = new HashMap<>();
        Map<String, Long> safeData = loadFromSafeCache(context, start);
        for (Map.Entry<String, Long> entry : safeData.entrySet()) {
            String pkg = entry.getKey();
            if (isValidAppCached(context, pkg, currentInstalledApps, launcherPkg, validityCache)) {
                restored.put(pkg, entry.getValue());
            }
        }
        return restored;
    }

    // Окно «однодневное», если целиком укладывается в сутки start (граница включительна).
    private static boolean isSingleDayWindow(long start, long end) {
        if (end <= start) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long nextDayStart = cal.getTimeInMillis();
        return end <= nextDayStart;
    }

    // === ИСПРАВЛЕНО: точное время за окно считаем из событий по модели «один передний слот» ===
    // queryAndAggregateUsageStats() суммирует getTotalTimeInForeground() по ДНЕВНЫМ
    // корзинам Android, границы которых НЕ выровнены по локальной полуночи. На
    // однодневном окне в сумму затекает «хвост» соседней корзины → завышение ~30%
    // (на неделе/месяце эта краевая доля мала, поэтому там было незаметно).
    //
    // Теперь идём по событиям queryEvents(start, end):
    //  - в каждый момент на переднем плане ровно одно приложение (currentPkg);
    //  - RESUMED/FOREGROUND другого пакета закрывает текущий слот и открывает новый;
    //  - PAUSED/STOPPED/BACKGROUND закрывает слот ТОЛЬКО если уходит именно текущий
    //    пакет; протухший STOPPED старого экрана при уже активном новом игнорируется
    //    (это убирает старое занижение ~30% при навигации внутри одного приложения);
    //  - каждый отрезок обрезается по [start, end] → нет «нахлёста» корзин и завышения;
    //  - события берём с запасом EVENT_LOOKBACK_MS до start, чтобы поймать сессию,
    //    начавшуюся до окна, и корректно обрезать её до start.
    private static Map<String, Long> fetchFromAndroidSystem(Context context, long start, long end, Set<String> currentInstalledApps, String launcherPkg, Map<String, Boolean> validityCache) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        long queryStart = start - EVENT_LOOKBACK_MS;
        if (queryStart < 0) queryStart = 0;

        UsageEvents events = usm.queryEvents(queryStart, end);
        if (events == null) return results;

        String currentPkg = null;
        long currentStart = 0L;

        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);

            int type = event.getEventType();
            long ts = event.getTimeStamp();
            String pkg = event.getPackageName();
            if (pkg != null) pkg = stripWhitespace(pkg);

            boolean isForeground = (type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    || (type == UsageEvents.Event.ACTIVITY_RESUMED);
            boolean isBackground = (type == UsageEvents.Event.MOVE_TO_BACKGROUND)
                    || (type == UsageEvents.Event.ACTIVITY_PAUSED)
                    || (type == UsageEvents.Event.ACTIVITY_STOPPED);

            if (isForeground) {
                // Закрываем предыдущий передний слот, если он был.
                if (currentPkg != null) {
                    creditSegment(results, currentPkg, currentStart, ts, start, end,
                            context, currentInstalledApps, launcherPkg, validityCache);
                }
                currentPkg = pkg;
                currentStart = ts;
            } else if (isBackground) {
                // Закрываем слот ТОЛЬКО если в фон уходит именно текущий пакет.
                if (currentPkg != null && currentPkg.equals(pkg)) {
                    creditSegment(results, currentPkg, currentStart, ts, start, end,
                            context, currentInstalledApps, launcherPkg, validityCache);
                    currentPkg = null;
                    currentStart = 0L;
                }
            }
        }

        // Если на конце окна слот всё ещё открыт — начисляем до end.
        if (currentPkg != null) {
            creditSegment(results, currentPkg, currentStart, end, start, end,
                    context, currentInstalledApps, launcherPkg, validityCache);
        }

        return results;
    }

    // Начисляет один foreground-отрезок пакету, обрезая его по границам окна [windowStart, windowEnd].
    private static void creditSegment(Map<String, Long> results, String pkg, long segStart, long segEnd,
                                      long windowStart, long windowEnd, Context context,
                                      Set<String> installedApps, String launcherPkg,
                                      Map<String, Boolean> validityCache) {
        if (pkg == null) return;
        long s = Math.max(segStart, windowStart);
        long e = Math.min(segEnd, windowEnd);
        long delta = e - s;
        if (delta <= 0) return;
        if (!isValidAppCached(context, pkg, installedApps, launcherPkg, validityCache)) return;
        Long current = results.get(pkg);
        results.put(pkg, (current == null ? 0L : current) + delta);
    }

    // === Месяц и Год: системные корзины, собираемые вручную ===
    public static Map<String, Long> getFilteredStats(Context context, int interval, long start, long end) {
        long durationDays = (end - start) / (1000 * 60 * 60 * 24);

        // Для периодов до 8 дней (Неделя/день) — точный foreground за окно.
        if (durationDays <= 8) {
            return getFilteredExactTimes(context, start, end);
        }

        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        Set<String> currentInstalledApps = getInstalledAppsCached(context);
        String launcherPkg = getDefaultLauncherCached(context);
        Map<String, Boolean> validityCache = new HashMap<>();

        // ГОД: годовые корзины (INTERVAL_YEARLY) — только они дают полную историю.
        // Плата — завышение из-за rollup-корзин, копящих время вне окна. Ниже для
        // года включается ОБРЕЗКА доли корзины, начавшейся раньше start.
        // Месяц приходит как INTERVAL_WEEKLY — его не трогаем (он точен).
        boolean isYear = (interval == UsageStatsManager.INTERVAL_YEARLY);
        int effectiveInterval = interval;

        List<UsageStats> statsList = usm.queryUsageStats(effectiveInterval, start, end);
        if (statsList != null) {
            for (UsageStats stats : statsList) {
                if (stats == null) continue;

                // Отсекаем корзины, не использованные после начала окна.
                if (stats.getLastTimeUsed() < start) {
                    continue;
                }

                String pkg = stats.getPackageName();
                if (pkg == null) continue;
                pkg = stripWhitespace(pkg);

                long time = stats.getTotalTimeInForeground();

                // === ОБРЕЗКА ЗАВЫШЕНИЯ ГОДА (только INTERVAL_YEARLY) ===
                if (isYear) {
                    long bucketFirst = stats.getFirstTimeStamp();
                    long bucketLast = stats.getLastTimeStamp();
                    if (bucketLast > bucketFirst && bucketFirst < start) {
                        long inWindow = bucketLast - start;
                        long bucketSpan = bucketLast - bucketFirst;
                        if (inWindow > 0 && inWindow < bucketSpan) {
                            time = (long) (time * ((double) inWindow / (double) bucketSpan));
                        }
                    }
                }

                if (time > 0 && isValidAppCached(context, pkg, currentInstalledApps, launcherPkg, validityCache)) {
                    long current = results.containsKey(pkg) ? results.get(pkg) : 0L;
                    results.put(pkg, current + time);
                }
            }
        }

        return results;
    }

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
            for (Map.Entry<String, Long> entry : data.entrySet()) json.put(entry.getKey(), entry.getValue());
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

    public static long sumMap(Map<String, Long> map) {
        long total = 0;
        if (map != null) {
            for (long time : map.values()) {
                total += time;
            }
        }
        return total;
    }

    // === Быстрое удаление пробелов без regex ===
    private static String stripWhitespace(String s) {
        if (s == null) return null;
        boolean has = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) { has = true; break; }
        }
        if (!has) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    // === Кэшированные обёртки над дорогими IPC-запросами ===
    private static Set<String> getInstalledAppsCached(Context context) {
        long now = System.currentTimeMillis();
        Set<String> cached = cachedInstalledApps;
        if (cached != null && (now - appListCacheTime) < APP_LIST_TTL_MS) {
            return cached;
        }
        Set<String> fresh = getInstalledApps(context);
        cachedInstalledApps = fresh;
        appListCacheTime = now;
        return fresh;
    }

    private static String getDefaultLauncherCached(Context context) {
        long now = System.currentTimeMillis();
        String cached = cachedLauncherPkg;
        if (cached != null && (now - appListCacheTime) < APP_LIST_TTL_MS) {
            return cached;
        }
        String fresh = getDefaultLauncher(context);
        cachedLauncherPkg = fresh;
        return fresh;
    }

    // === Мемоизация isValidApp ===
    private static boolean isValidAppCached(Context context, String pkg, Set<String> installedApps, String launcherPkg, Map<String, Boolean> cache) {
        if (pkg == null) return false;
        Boolean cached = cache.get(pkg);
        if (cached != null) return cached;
        boolean valid = isValidApp(context, pkg, installedApps, launcherPkg);
        cache.put(pkg, valid);
        return valid;
    }

    private static Set<String> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) {
            apps.add(stripWhitespace(info.activityInfo.packageName));
        }
        return apps;
    }

    private static String getDefaultLauncher(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? stripWhitespace(defaultLauncher.activityInfo.packageName) : "";
    }

    private static boolean isValidApp(Context context, String pkg, Set<String> installedApps, String launcherPkg) {
        if (pkg == null) return false;
        String lowerPkg = pkg.toLowerCase();

        boolean isSystemTrash = pkg.equals("android") ||
                pkg.equals("com.android.systemui") ||
                pkg.equals("com.google.android.gms") ||
                pkg.equals("com.android.vending") ||
                lowerPkg.contains("settings") ||
                lowerPkg.contains("launcher") ||
                lowerPkg.contains("bluestacks") ||
                lowerPkg.contains("documentsui") ||
                lowerPkg.contains("filemanager") ||
                pkg.equals(launcherPkg);

        if (isSystemTrash) return false;
        if (installedApps.contains(pkg)) return true;

        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            try {
                int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ?
                        PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, flag);

                boolean isSystemApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUpdatedSystemApp = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

                if (isSystemApp || isUpdatedSystemApp) return false;
            } catch (Exception ignored) {}
            return true;
        }
    }
}
