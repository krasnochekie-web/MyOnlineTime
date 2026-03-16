package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.Spinner;
import android.widget.TextView;
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

public class StatsFragment extends Fragment {

    // --- НАШ КЭШ В ПАМЯТИ ---
    private class CachedStats {
        List<String> list;
        Map<String, Long> times;
        long totalMillis;
        CachedStats(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }
    private Map<Integer, CachedStats> statsCache = new HashMap<>();
    
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

// Слушатель для графика
btnChart.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        // TODO: Здесь будет логика открытия графика
    }
});

// Слушатель для кнопки "За все время"
btnAllTime.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        // TODO: Здесь будет логика подсчета "За все время" (о которой мы говорили ранее)
    }
});
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        final AppsAdapter adapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        recyclerView.setAdapter(adapter);

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
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

                        final Handler uiHandler = new Handler(Looper.getMainLooper());
                        
                        uiHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isAdded()) return;
                                
                                totalTimeText.setText(activity.getString(R.string.loading));
                                
                                ExecutorService executor = Executors.newSingleThreadExecutor();
                                executor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        // --- НАЧАЛО ТЯЖЕЛОЙ МАТЕМАТИКИ ---
                                        Calendar cal = Calendar.getInstance(); 
                                        long now = System.currentTimeMillis();
                                        long startTime = now;
                                        long endTime = now; // Добавлен endTime (важно для "Вчера")
                                        
                                        if (position == 0) { // Сегодня
                                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); 
                                            startTime = cal.getTimeInMillis(); 
                                        } 
                                        else if (position == 1) { // Вчера
                                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); 
                                            endTime = cal.getTimeInMillis(); // Конец вчерашнего дня - это начало сегодняшнего
                                            cal.add(Calendar.DAY_OF_YEAR, -1);
                                            startTime = cal.getTimeInMillis(); // Начало вчерашнего дня
                                        } 
                                        else if (position == 2) { cal.add(Calendar.DAY_OF_YEAR, -7); startTime = cal.getTimeInMillis(); } // Неделя
                                        else if (position == 3) { cal.add(Calendar.MONTH, -1); startTime = cal.getTimeInMillis(); } // Месяц
                                        else { cal.add(Calendar.YEAR, -1); startTime = cal.getTimeInMillis(); } // Год
                                        
                                        UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
                                        final Map<String, Long> exactTimes = new HashMap<>();
                                        long tempTotalMillis = 0;
                                        
                                        if (usm != null) {
                                            if (position == 0 || position == 1) {
                                                // --- ИДЕАЛЬНЫЙ ПОДСЧЕТ ДЛЯ "ЗА СУТКИ" И "ВЧЕРА" ---
                                                // Используем endTime вместо now, чтобы "Вчера" обрезалось ровно в 00:00
                                                android.app.usage.UsageEvents events = usm.queryEvents(startTime, endTime);
                                                android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
                                                
                                                Map<String, Long> startTimes = new HashMap<>(); 
                                                Set<String> handledOrphans = new HashSet<>(); 
                                                
                                                while (events.hasNextEvent()) {
                                                    events.getNextEvent(event);
                                                    String pkg = event.getPackageName();
                                                    int type = event.getEventType();
                                                    long timestamp = event.getTimeStamp();
                                                    
                                                    if (type == 1) {
                                                        startTimes.put(pkg, timestamp);
                                                    } 
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
                                                            long duration = timestamp - startTime;
                                                            if (duration > 0) {
                                                                Long current = exactTimes.get(pkg);
                                                                exactTimes.put(pkg, (current == null ? 0 : current) + duration);
                                                            }
                                                            handledOrphans.add(pkg);
                                                        }
                                                    }
                                                }
                                                
                                                // Для приложений, которые закрыли уже после endTime (переход через полночь)
                                                for (Map.Entry<String, Long> entry : startTimes.entrySet()) {
                                                    long duration = endTime - entry.getValue(); // ТУТ ВАЖНО: endTime вместо now!
                                                    if (duration > 0) {
                                                        String pkg = entry.getKey();
                                                        Long current = exactTimes.get(pkg);
                                                        exactTimes.put(pkg, (current == null ? 0 : current) + duration);
                                                    }
                                                }
                                            } else {
                                                // --- НОВЫЙ ТОЧНЫЙ МЕТОД ДЛЯ НЕДЕЛИ, МЕСЯЦА, ГОДА ---
                                                // Ручное сложение суточных интервалов решает проблему завышения времени в 2 раза!
                                                List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
                                                if (statsList != null) {
                                                    for (UsageStats stats : statsList) {
                                                        long time = stats.getTotalTimeInForeground();
                                                        if (time > 0) {
                                                            String pkg = stats.getPackageName();
                                                            Long current = exactTimes.get(pkg);
                                                            exactTimes.put(pkg, (current == null ? 0 : current) + time);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        
                                        PackageManager pm = activity.getPackageManager();
                                        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                                        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                                        List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
                                        Set<String> userApps = new HashSet<>();
                                        for (android.content.pm.ResolveInfo info : resolvedInfos) { userApps.add(info.activityInfo.packageName); }
                                        
                                        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                                        homeIntent.addCategory(Intent.CATEGORY_HOME);
                                        android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
                                        String launcherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
                                        
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
                                        
                                        Collections.sort(finalList, new Comparator<String>() {
                                            @Override public int compare(String left, String right) {
                                                Long tLeft = exactTimes.get(left); Long tRight = exactTimes.get(right);
                                                if (tLeft == null) tLeft = 0L; if (tRight == null) tRight = 0L;
                                                return Long.compare(tRight, tLeft);
                                            }
                                        });
                                        final long finalTotalMillis = tempTotalMillis;
                                        
                                        uiHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isAdded()) {
                                                    statsCache.put(position, new CachedStats(finalList, exactTimes, finalTotalMillis));
                                                    totalTimeText.setText(Utils.formatTime(activity, finalTotalMillis));
                                                    adapter.updateData(finalList, exactTimes);
                                                }
                                            }
                                        });
                                    }
                                }); 
                            }
                        }, 300);
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                }); 
                
                if (spinner.getSelectedItemPosition() >= 0) {
                    spinner.getOnItemSelectedListener().onItemSelected(spinner, null, spinner.getSelectedItemPosition(), 0);
                }
                
            }
        }, 300);
        return view;
    }
}
