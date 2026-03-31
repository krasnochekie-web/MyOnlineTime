package com.myonlinetime.app.ui;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

public class ChartFragment extends Fragment {

    private WeeklyBarChartView barChart;
    private TextView topDateTxt, topTimeTxt, middleDateTxt;
    private ImageView btnPrev, btnNext;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;

    private final List<DayData> weeklyData = new ArrayList<>();
    private int currentIndex = 6; 

    static class DayData {
        long totalMillis;
        String dayOfWeekShort;
        String dateTopStr;
        String dateMiddleStr;
        List<String> appList;
        Map<String, Long> appTimes;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_chart, container, false);
        final MainActivity activity = (MainActivity) getActivity();

        barChart = view.findViewById(R.id.weekly_bar_chart);
        topDateTxt = view.findViewById(R.id.chart_top_date);
        topTimeTxt = view.findViewById(R.id.chart_top_time);
        middleDateTxt = view.findViewById(R.id.chart_middle_date);
        btnPrev = view.findViewById(R.id.btn_prev_day);
        btnNext = view.findViewById(R.id.btn_next_day);
        recyclerView = view.findViewById(R.id.chart_apps_list);

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        
        // ВАЖНО: Убрали флаг false! Адаптер теперь чистый.
        adapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        recyclerView.setAdapter(adapter);

        barChart.setListener(this::selectDay);

        btnPrev.setOnClickListener(v -> {
            if (currentIndex > 0) selectDay(currentIndex - 1);
        });

        btnNext.setOnClickListener(v -> {
            if (currentIndex < 6) selectDay(currentIndex + 1);
        });

        loadWeeklyData();
        return view;
    }

    private void selectDay(int index) {
        if (weeklyData.isEmpty() || index < 0 || index > 6) return;
        currentIndex = index;
        
        barChart.setSelectedIndex(index);
        DayData data = weeklyData.get(index);

        topDateTxt.setText(data.dateTopStr);
        middleDateTxt.setText(data.dateMiddleStr);
        topTimeTxt.setText(Utils.formatTime(getContext(), data.totalMillis));
        
        adapter.updateData(data.appList, data.appTimes);

        btnPrev.setAlpha(currentIndex == 0 ? 0.3f : 1.0f);
        btnNext.setAlpha(currentIndex == 6 ? 0.3f : 1.0f);
    }

    private void loadWeeklyData() {
        topDateTxt.setText(getString(R.string.loading));
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || !isAdded()) return;

            PackageManager pm = activity.getPackageManager();
            Set<String> userApps = getUserApps(pm);
            String launcherPkg = getLauncherPackage(pm);

            String[] daysArray = getResources().getStringArray(R.array.days_short);
            String[] monthsArray = getResources().getStringArray(R.array.months_custom);

            long[] barMillis = new long[7];
            String[] dayLabels = new String[7];

            weeklyData.clear();

            for (int i = 0; i < 7; i++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -(6 - i)); 
                
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sunday
                String dayShort = daysArray[dayOfWeek];
                
                int dayNum = cal.get(Calendar.DAY_OF_MONTH);
                int monthNum = cal.get(Calendar.MONTH);
                String monthStr = monthsArray[monthNum];

                String topFormatted = getString(R.string.format_date_top, dayShort, dayNum, monthStr);
                String middleFormatted = getString(R.string.format_date_middle, dayShort, dayNum, monthStr);

                Calendar startCal = (Calendar) cal.clone();
                startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0); startCal.set(Calendar.SECOND, 0);
                Calendar endCal = (Calendar) cal.clone();
                endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59); endCal.set(Calendar.SECOND, 59);

                Map<String, Long> times = calculateFromEventsDay(activity, startCal.getTimeInMillis(), endCal.getTimeInMillis());
                
                long dailyTotal = 0;
                List<String> apps = new ArrayList<>();
                for (Map.Entry<String, Long> entry : times.entrySet()) {
                    String pkg = entry.getKey();
                    long time = entry.getValue();
                    
                    boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                            pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                            pkg.equals(launcherPkg);
                                            
                    if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) { 
                        apps.add(pkg);
                        dailyTotal += time;
                    }
                }
                Collections.sort(apps, (left, right) -> Long.compare(times.get(right), times.get(left)));

                DayData dayData = new DayData();
                dayData.totalMillis = dailyTotal;
                dayData.dayOfWeekShort = dayShort;
                dayData.dateTopStr = topFormatted;
                dayData.dateMiddleStr = middleFormatted;
                dayData.appList = apps;
                dayData.appTimes = times;
                
                weeklyData.add(dayData); 
                barMillis[i] = dailyTotal;
                dayLabels[i] = dayShort;
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                barChart.setData(barMillis, dayLabels);
                selectDay(6); // Сразу выбираем сегодняшний день
            });
        });
    }

    private Map<String, Long> calculateFromEventsDay(Context context, long start, long end) {
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

    private Set<String> getUserApps(PackageManager pm) {
        Set<String> apps = new HashSet<>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : resolvedInfos) { apps.add(info.activityInfo.packageName); }
        return apps;
    }
    
    private String getLauncherPackage(PackageManager pm) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
    }
}
