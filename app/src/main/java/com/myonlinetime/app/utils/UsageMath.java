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
    // getInstalledApps()/getDefaultLauncher() — это binder-вызовы. При старте они
    // дёргаются несколько раз подряд (preloadCoreStats + профиль), поэтому кэшируем
    // на короткое время. TTL маленький, чтобы свежеустановленные приложения
    // подхватывались почти сразу.
    private static final long APP_LIST_TTL_MS = 15_000L;
    private static volatile Set<String> cachedInstalledApps = null;
    private static volatile String cachedLauncherPkg = null;
    private static volatile long appListCacheTime = 0L;

    // === Актуализация границ суток ===
    // Границы дня кэшируются в статике. Если процесс живёт через полночь, они
    // протухают, и окно вида [todayStartMillis, now] начинает охватывать двое
    // суток — это и приводило к «дописыванию» данных в уже истёкший день.
    // Метод пересчитывает границы и сбрасывает посуточные кэши при смене дня.
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
            // День сменился — старые «сегодня/вчера» больше не валидны.
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
        // Многодневные окна (неделя/год, либо устаревший todayStartMillis после
        // полуночи) НЕ должны касаться посуточного кэша — иначе сумма за несколько
        // суток перезапишет данные уже закрытого дня.
        if (!isSingleDayWindow(start, end)) {
            return systemData;
        }

        if (!systemData.isEmpty()) {
            // События за этот день ещё доступны в системе (диапазон графика — 7 дней,
            // а система хранит события ~7–14 дней). Значит система = источник истины.
            // Перезаписываем слот дня значением по событиям (set, НЕ max), чтобы
            // истёкший день «замораживался» на правде и не рос со временем.
            saveToSafeCache(context, start, systemData);
            return systemData;
        }

        // События за этот день уже вытеснены системой (старый день вне окна
        // хранения) — отдаём то, что успели сохранить раньше.
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

    // Окно считается «однодневным», если оно целиком укладывается в сутки,
    // которым принадлежит start. Граница включительна (23:59:59.999 или ровно
    // полночь следующего дня — как у окна «вчера» [yStart, todayStart]).
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

    private static Map<String, Long> fetchFromAndroidSystem(Context context, long start, long end, Set<String> currentInstalledApps, String launcherPkg, Map<String, Boolean> validityCache) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openTimes = new HashMap<>();
        // Пакеты, для которых мы уже видели хотя бы одно событие в окне.
        // Нужно, чтобы корректно учесть сессию, начавшуюся ДО начала периода
        // (первое событие пакета — PAUSE/STOP без предшествующего RESUME).
        Set<String> seenPkg = new HashSet<>();
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getPackageName() == null) continue;

            String pkg = stripWhitespace(event.getPackageName());
            int type = event.getEventType();

            boolean firstForPkg = !seenPkg.contains(pkg);
            seenPkg.add(pkg);

            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                // ВАЖНО: не перезаписываем уже открытую сессию. При переходах между
                // активностями/экранами ОДНОГО приложения RESUMED приходит повторно
                // без промежуточного PAUSED. Если перезаписать openTimes более поздним
                // таймштампом — начало сессии теряется и время ЗАНИЖАЕТСЯ. Поэтому
                // храним САМЫЙ РАННИЙ resume и закрываем сессию только по PAUSE/STOP.
                if (!openTimes.containsKey(pkg)) {
                    openTimes.put(pkg, event.getTimeStamp());
                }
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                    type == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (openTimes.containsKey(pkg)) {
                    long duration = event.getTimeStamp() - openTimes.get(pkg);
                    if (duration > 0 && isValidAppCached(context, pkg, currentInstalledApps, launcherPkg, validityCache)) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                    openTimes.remove(pkg);
                } else if (firstForPkg) {
                    // Первое событие пакета в окне — PAUSE/STOP, значит приложение было
                    // на переднем плане ещё до начала периода. Считаем от начала окна.
                    // Это убирает занижение за день и за неделю на стыке полуночи/границы.
                    long duration = event.getTimeStamp() - start;
                    if (duration > 0 && isValidAppCached(context, pkg, currentInstalledApps, launcherPkg, validityCache)) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
                }
            }
        }

        long endToUse = Math.min(end, System.currentTimeMillis());
        for (Map.Entry<String, Long> entry : openTimes.entrySet()) {
            String pkg = entry.getKey();
            long duration = endToUse - entry.getValue();
            if (duration > 0 && isValidAppCached(context, pkg, currentInstalledApps, launcherPkg, validityCache)) {
                Long current = results.get(pkg);
                results.put(pkg, (current == null ? 0L : current) + duration);
            }
        }
        return results;
    }

    // === ИСПРАВЛЕНИЕ: УМНАЯ АГРЕГАЦИЯ БЕЗ ЗАВЫШЕНИЙ И БЕЗ ЗАНИЖЕНИЙ ===
    public static Map<String, Long> getFilteredStats(Context context, int interval, long start, long end) {
        long durationDays = (end - start) / (1000 * 60 * 60 * 24);

        // 1. Для периодов до 8 дней (Неделя) используем ИДЕАЛЬНУЮ точность по миллисекундам!
        // Android хранит события (Events) около 10-14 дней, поэтому мы можем полностью избежать "корзин".
        if (durationDays <= 8) {
            return getFilteredExactTimes(context, start, end);
        }

        // 2. Для Месяца и Года используем системные корзины, но собираем их ВРУЧНУЮ, отсеивая "мусор"
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        Set<String> currentInstalledApps = getInstalledAppsCached(context);
        String launcherPkg = getDefaultLauncherCached(context);
        Map<String, Boolean> validityCache = new HashMap<>();

        // ФИКС ГОДА (занижение в ~2.5 раза): INTERVAL_YEARLY давал перекрывающиеся
        // раздутые корзины → завышение, а INTERVAL_MONTHLY — слишком грубый, система
        // теряет в нём foreground-время → сильное занижение. Берём НЕДЕЛЬНЫЕ корзины,
        // ровно как считается корректно работающий «Месяц»: они не перекрываются и
        // достаточно мелкие, чтобы не терять время. Год выравнивается по месяцу.
        int effectiveInterval = interval;
        if (interval == UsageStatsManager.INTERVAL_YEARLY) {
            effectiveInterval = UsageStatsManager.INTERVAL_WEEKLY;
        }

        // Получаем сырые корзины вместо кривого агрегатора
        List<UsageStats> statsList = usm.queryUsageStats(effectiveInterval, start, end);
        if (statsList != null) {
            for (UsageStats stats : statsList) {
                if (stats == null) continue;

                // ГЛАВНЫЙ ФИЛЬТР: Отсекаем корзины, если приложение в них фактически использовалось ДО начала нашего периода.
                // Это убивает львиную долю погрешности на стыке месяцев/лет.
                if (stats.getLastTimeUsed() < start) {
                    continue;
                }

                String pkg = stats.getPackageName();
                if (pkg == null) continue;
                pkg = stripWhitespace(pkg);

                long time = stats.getTotalTimeInForeground();
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

    // === Быстрое удаление пробелов без regex (вызывается в горячем цикле событий) ===
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
        // appListCacheTime обновляется в getInstalledAppsCached; здесь не сбрасываем,
        // чтобы оба значения старели согласованно.
        return fresh;
    }

    // === Мемоизация isValidApp: каждый уникальный пакет проверяется один раз ===
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
