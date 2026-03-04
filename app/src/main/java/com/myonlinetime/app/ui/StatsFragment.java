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

    // Пустой конструктор (обязательно для Fragment)
    public StatsFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 1. Надуваем разметку один раз
        View view = inflater.inflate(R.layout.layout_usage, container, false);
        
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        final RecyclerView recyclerView = view.findViewById(R.id.apps_list);
        Spinner spinner = view.findViewById(R.id.spinner_period);
        final TextView totalTimeText = view.findViewById(R.id.text_total_time_sum);

        // Настраиваем RecyclerView (говорим ему быть списком)
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));

        // Создаем наш новый мощный адаптер (он теперь принимает только контекст)
        final AppsAdapter adapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        recyclerView.setAdapter(adapter);

        // 3. Настройка спиннера
        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, final int position, long id) {
                if (activity == null) return;

                // 1. ПОКАЗЫВАЕМ СОСТОЯНИЕ ЗАГРУЗКИ (Опционально)
                totalTimeText.setText(activity.getString(R.string.loading)); 
                adapter.updateData(new ArrayList<String>(), new HashMap<String, Long>()); // Очищаем старый список

                // 2. ОТПРАВЛЯЕМ ТЯЖЕЛУЮ РАБОТУ В ФОНОВЫЙ ПОТОК
                ExecutorService executor = Executors.newSingleThreadExecutor();
                final Handler handler = new Handler(Looper.getMainLooper());

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // === ЭТОТ КОД РАБОТАЕТ В ФОНЕ (НЕ ТОРМОЗИТ АНИМАЦИЮ) ===
                        Calendar cal = Calendar.getInstance(); 
                        long now = System.currentTimeMillis();
                        long startTime = now;
                        int intervalType = UsageStatsManager.INTERVAL_DAILY;
                        
                        if (position == 0) { 
                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); 
                            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); 
                            startTime = cal.getTimeInMillis(); 
                            intervalType = UsageStatsManager.INTERVAL_DAILY;
                        } 
                        else if (position == 1) { 
                            cal.add(Calendar.DAY_OF_YEAR, -7); 
                            startTime = cal.getTimeInMillis(); 
                            intervalType = UsageStatsManager.INTERVAL_WEEKLY; 
                        } 
                        else if (position == 2) { 
                            cal.add(Calendar.MONTH, -1); 
                            startTime = cal.getTimeInMillis(); 
                            intervalType = UsageStatsManager.INTERVAL_MONTHLY; 
                        } 
                        else { 
                            cal.add(Calendar.YEAR, -1); 
                            startTime = cal.getTimeInMillis(); 
                            intervalType = UsageStatsManager.INTERVAL_YEARLY; 
                        }
                        
                        UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
                        final Map<String, Long> exactTimes = new HashMap<>();
                        long tempTotalMillis = 0;
                        
                        if (usm != null) {
                            List<UsageStats> stats = usm.queryUsageStats(intervalType, startTime, now);
                            if (stats != null) {
                                for (UsageStats stat : stats) {
                                    long time = stat.getTotalTimeInForeground();
                                    if (time > 0) exactTimes.put(stat.getPackageName(), exactTimes.getOrDefault(stat.getPackageName(), 0L) + time);
                                }
                            }
                        }
                        
                        PackageManager pm = activity.getPackageManager();
                        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                        List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
                        Set<String> userApps = new HashSet<>();
                        for (android.content.pm.ResolveInfo info : resolvedInfos) {
                            userApps.add(info.activityInfo.packageName);
                        }
                        
                        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                        homeIntent.addCategory(Intent.CATEGORY_HOME);
                        android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
                        String launcherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
                        
                        final List<String> finalList = new ArrayList<>();
                        for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
                            String pkg = entry.getKey();
                            long time = entry.getValue();
                            boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                                    pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                                    pkg.equals(launcherPkg);
                                                    
                            if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                                finalList.add(pkg);
                                tempTotalMillis += time;
                            }
                        }
                        
                        Collections.sort(finalList, new Comparator<String>() {
                            @Override
                            public int compare(String left, String right) {
                                Long tLeft = exactTimes.get(left);
                                Long tRight = exactTimes.get(right);
                                if (tLeft == null) tLeft = 0L;
                                if (tRight == null) tRight = 0L;
                                return Long.compare(tRight, tLeft);
                            }
                        });

                        final long finalTotalMillis = tempTotalMillis;

                        // 3. ВОЗВРАЩАЕМСЯ В ГЛАВНЫЙ ПОТОК, ЧТОБЫ ОБНОВИТЬ ЭКРАН
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isAdded()) {
                                    totalTimeText.setText(Utils.formatTime(activity, finalTotalMillis));
                                    adapter.updateData(finalList, exactTimes);
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            } 
        });  
        return view; 
    } 
}