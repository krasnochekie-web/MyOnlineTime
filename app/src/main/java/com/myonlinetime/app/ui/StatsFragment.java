package com.myonlinetime.app.ui;

import android.app.usage.UsageEvents;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsFragment extends Fragment {

    // --- КЭШ В ПАМЯТИ ---
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

        // Инициализация слушателей кнопок (вы уже делали это, просто убедитесь, что они есть)
        btnChart.setOnClickListener(v -> { /* TODO: Логика графика */ });
        btnAllTime.setOnClickListener(v -> { /* TODO: Логика "за всё время" */ });
        
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        final AppsAdapter adapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        recyclerView.setAdapter(adapter);

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        // Отложенная загрузка для плавной анимации
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

                    // Ждем закрытия спиннера, затем запускаем подсчет
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isAdded()) return;
                        
                        totalTimeText.setText(activity.getString(R.string.loading));
                        
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            // --- НАЧАЛО ТОЧНОГО ПОДСЧЕТА ---
                            Calendar cal = Calendar.getInstance(); 
                            long endTime = System.currentTimeMillis();
                            long startTime;
                            
                            switch (position) {
                                case 0: // Сегодня
                                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);
                                    startTime = cal.getTimeInMillis();
                                    break;
                                case 1: // Вчера
                                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);
                                    endTime = cal.getTimeInMillis();
                                    cal.add(Calendar.DAY_OF_YEAR, -1);
                                    startTime = cal.getTimeInMillis();
                                    break;
                                case 2: // Неделя
                                    cal.add(Calendar.DAY_OF_YEAR, -7);
                                    startTime = cal.getTimeInMillis();
                                    break;
                                case 3: // Месяц
                                    cal.add(Calendar.MONTH, -1);
                                    startTime = cal.getTimeInMillis();
                                    break;
                                default: // Год
                                    cal.add(Calendar.YEAR, -1);
                                    startTime = cal.getTimeInMillis();
                                    break;
                            }
                            
                            // --- ГЛАВНОЕ ИСПРАВЛЕНИЕ: ИСПОЛЬЗУЕМ QUERYEVENTS ДЛЯ ВСЕХ ПЕРИОДОВ ---
                            final Map<String, Long> exactTimes = calculateExactTimes(activity, startTime, endTime);
                            long tempTotalMillis = 0;

                            PackageManager pm = activity.getPackageManager();
                            Set<String> userApps = getUserApps(pm);
                            String launcherPkg = getLauncherPackage(pm);
                            
                            final List<String> finalList = new ArrayList<>();
                            for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
                                String pkg = entry.getKey();
                                long time = entry.getValue();
                                boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || pkg.equals(launcherPkg);
                                if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                                    finalList.add(pkg);
                                    tempTotalMillis += time;
                                }
                            }
                            
                            Collections.sort(finalList, (left, right) -> {
                                Long tLeft = exactTimes.get(left); Long tRight = exactTimes.get(right);
                                if (tLeft == null) tLeft = 0L; if (tRight == null) tRight = 0L;
                                return Long.compare(tRight, tLeft);
                            });

                            final long finalTotalMillis = tempTotalMillis;
                            // --- КОНЕЦ ПОДСЧЕТА ---
                            
                            // Возврат в UI-поток
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
            
            // Первый запуск
            if (spinner.getSelectedItemPosition() >= 0) {
                spinner.getOnItemSelectedListener().onItemSelected(spinner, null, spinner.getSelectedItemPosition(), 0);
            }
        }, 300);
        return view;
    }

    /**
     * Самый точный метод подсчета времени через события.
     */
    private Map<String, Long> calculateExactTimes(Context context, long startTime, long endTime) {
        Map<String, Long> exactTimes = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return exactTimes;

        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        
        Map<String, Long> startTimes = new HashMap<>(); 
        Set<String> handledOrphans = new HashSet<>();
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();
            int type = event.getEventType();
            long timestamp = event.getTimeStamp();
            
            // 1 = ACTIVITY_RESUMED (Приложение появилось на экране)
            if (type == 1) {
                startTimes.put(pkg, timestamp);
            } 
            // 2 = ACTIVITY_PAUSED (Приложение ушло с экрана)
            else if (type == 2) {
                if (startTimes.containsKey(pkg)) {
                    long start = startTimes.get(pkg);
                    long duration = timestamp - start;
                    if (duration > 0) {
                        Long current = exactTimes.get(pkg);
                        exactTimes.put(pkg, (current == null ? 0 : current) + duration);
                    }
                    startTimes.remove(pkg);
                } else if (!handledOrphans.contains(pkg)) {
                    // Сессия, которая началась до startTime, а закончилась после
                    long duration = timestamp - startTime;
                    if (duration > 0) {
                        Long current = exactTimes.get(pkg);
                        exactTimes.put(pkg, (current == null ? 0 : current) + duration);
                    }
                    handledOrphans.add(pkg);
                }
            }
        }
        
        // Для приложений, которые были открыты в момент вызова (еще нет события закрытия)
        for (Map.Entry<String, Long> entry : startTimes.entrySet()) {
            long duration = endTime - entry.getValue();
            if (duration > 0) {
                String pkg = entry.getKey();
                Long current = exactTimes.get(pkg);
                exactTimes.put(pkg, (current == null ? 0 : current) + duration);
            }
        }
        return exactTimes;
    }

    private Set<String> getUserApps(PackageManager pm) {
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (android.content.pm.ResolveInfo info : resolvedInfos) { 
            apps.add(info.activityInfo.packageName); 
        }
        return apps;
    }
    
    private String getLauncherPackage(PackageManager pm) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
    }
}
