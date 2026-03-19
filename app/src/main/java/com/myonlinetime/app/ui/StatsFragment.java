package com.myonlinetime.app.ui;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.adapters.AppsAdapter;
import com.myonlinetime.app.utils.Utils;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsFragment extends Fragment {

    private static class CachedStats {
        List<String> list;
        Map<String, Long> times;
        long totalMillis;
        CachedStats(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }
    private final Map<Integer, CachedStats> statsCache = new HashMap<>();
    
    public StatsFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_usage, container, false);
        
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        final RecyclerView recyclerView = view.findViewById(R.id.apps_list);
        final Spinner spinner = view.findViewById(R.id.spinner_period);
        final TextView totalTimeText = view.findViewById(R.id.text_total_time_sum);
        final TextView btnChart = view.findViewById(R.id.btn_chart);
        final TextView btnAllTime = view.findViewById(R.id.btn_all_time);

        btnChart.setOnClickListener(v -> { /* Логика графика */ });
        btnAllTime.setOnClickListener(v -> { /* Логика "за всё время" */ });
        
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        final AppsAdapter adapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        recyclerView.setAdapter(adapter);

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded() || activity == null) return;

            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View v, final int position, long id) {
                    if (statsCache.containsKey(position)) {
                        CachedStats cached = statsCache.get(position);
                        totalTimeText.setText(Utils.formatTime(activity, cached.totalMillis));
                        adapter.updateData(cached.list, cached.times);
                        return;
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isAdded()) return;
                        totalTimeText.setText(activity.getString(R.string.loading));
                        
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            Calendar cal = Calendar.getInstance(); 
                            long endTime = System.currentTimeMillis();
                            long startTime;
                            int interval;
                            
                            // --- ИСПОЛЬЗУЕМ "КИРПИЧИКИ" ДЛЯ ИДЕАЛЬНОЙ СБОРКИ ПЕРИОДОВ ---
                            switch (position) {
                                case 0: // Сегодня
                                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);
                                    startTime = cal.getTimeInMillis();
                                    interval = -1; // Используем события
                                    break;
                                case 1: // Вчера
                                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);
                                    endTime = cal.getTimeInMillis();
                                    cal.add(Calendar.DAY_OF_YEAR, -1);
                                    startTime = cal.getTimeInMillis();
                                    interval = -1; // Используем события
                                    break;
                                case 2: // Неделя
                                    cal.add(Calendar.DAY_OF_YEAR, -7);
                                    startTime = cal.getTimeInMillis();
                                    // Складываем 7 ежедневных бакетов
                                    interval = UsageStatsManager.INTERVAL_DAILY; 
                                    break;
                                case 3: // Месяц
                                    cal.add(Calendar.MONTH, -1);
                                    startTime = cal.getTimeInMillis();
                                    // Складываем 4 недельных бакета (а не запрашиваем месяц целиком!)
                                    interval = UsageStatsManager.INTERVAL_WEEKLY; 
                                    break;
                                default: // Год
                                    cal.add(Calendar.YEAR, -1);
                                    startTime = cal.getTimeInMillis();
                                    // Складываем 12 месячных бакетов
                                    interval = UsageStatsManager.INTERVAL_MONTHLY; 
                                    break;
                            }
                            
                            final Map<String, Long> exactTimes;
                            if (interval == -1) {
                                exactTimes = calculateFromEvents(activity, startTime, endTime);
                            } else {
                                exactTimes = calculateFromStats(activity, interval, startTime, endTime);
                            }
                            
                            long tempTotalMillis = 0;
                            PackageManager pm = activity.getPackageManager();
                            Set<String> userApps = getUserApps(pm);
                            String launcherPkg = getLauncherPackage(pm);
                            
                            final List<String> finalList = new ArrayList<>();
                            for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
                                String pkg = entry.getKey();
                                long time = entry.getValue();
                                boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || pkg.equals(launcherPkg);
                                if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) {
                                    finalList.add(pkg);
                                    tempTotalMillis += time;
                                }
                            }
                            
                            Collections.sort(finalList, (left, right) -> Long.compare(exactTimes.get(right), exactTimes.get(left)));
                            final long finalTotalMillis = tempTotalMillis;
                            
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (isAdded()) {
                                    statsCache.put(position, new CachedStats(finalList, exactTimes, finalTotalMillis));
                                    totalTimeText.setText(Utils.formatTime(activity, finalTotalMillis));
                                    adapter.updateData(finalList, exactTimes);
                                }
                            });
                        }); 
                    }, 300);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            }); 
            
            if (spinner.getSelectedItemPosition() >= 0) {
                spinner.getOnItemSelectedListener().onItemSelected(spinner, null, spinner.getSelectedItemPosition(), 0);
            }
        }, 300);
        return view;
    }

    // МЕТОД 1: Через события (Идеально для Сегодня/Вчера)
    private Map<String, Long> calculateFromEvents(Context context, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

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
                    if (duration > 0) results.put(pkg, results.getOrDefault(pkg, 0L) + duration);
                    openTimes.remove(pkg);
                }
            }
        }
        return results;
    }

    // МЕТОД 2: Через суммирование "кирпичиков" (Для Недели, Месяца, Года)
    private Map<String, Long> calculateFromStats(Context context, int interval, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        List<UsageStats> stats = usm.queryUsageStats(interval, start, end);
        if (stats != null) {
            for (UsageStats s : stats) {
                long time = s.getTotalTimeInForeground();
                if (time > 0) {
                    // --- ИСПРАВЛЕНИЕ: МЫ СУММИРУЕМ, А НЕ ИСПОЛЬЗУЕМ Math.max ---
                    results.put(s.getPackageName(), results.getOrDefault(s.getPackageName(), 0L) + time);
                }
            }
        }
        return results;
    }

    private Set<String> getUserApps(PackageManager pm) {
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (android.content.pm.ResolveInfo info : resolvedInfos) { apps.add(info.activityInfo.packageName); }
        return apps;
    }
    
    private String getLauncherPackage(PackageManager pm) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
    }
}
