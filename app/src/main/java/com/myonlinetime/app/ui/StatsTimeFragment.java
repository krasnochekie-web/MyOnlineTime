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
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
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

public class StatsTimeFragment extends Fragment {

    private static class CachedStats {
        List<String> list;
        Map<String, Long> times;
        long totalMillis;
        CachedStats(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }
    private final Map<Integer, CachedStats> statsCache = new HashMap<>();
    
    public StatsTimeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();

        // 1. Инфлейтим наш новый пустой экран с одним RecyclerView
        final View view = inflater.inflate(R.layout.layout_time_tab, container, false);
        final RecyclerView recyclerView = view.findViewById(R.id.stats_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));

        // 2. Инфлейтим шапку и подвал из новых XML-файлов
        View headerView = inflater.inflate(R.layout.layout_stats_header, recyclerView, false);
        View footerView = inflater.inflate(R.layout.layout_stats_footer, recyclerView, false);

        // 3. Ищем элементы ВНУТРИ шапки и подвала!
        final Spinner spinner = headerView.findViewById(R.id.spinner_period);
        final TextView totalTimeText = headerView.findViewById(R.id.text_total_time_sum);
        final TextView textWeek = footerView.findViewById(R.id.text_time_week);
        final TextView textMonth = footerView.findViewById(R.id.text_time_month);
        final TextView textYear = footerView.findViewById(R.id.text_time_year);

        // 4. Инициализируем 3 адаптера
        SingleViewAdapter headerAdapter = new SingleViewAdapter(headerView);
        // ВАЖНО: Никаких true/false в конце, используем наш обновленный тупой адаптер!
        final AppsAdapter appsAdapter = new AppsAdapter(activity, R.layout.item_app_usage_time);
        SingleViewAdapter footerAdapter = new SingleViewAdapter(footerView);

        // 5. СКЛЕИВАЕМ ИХ С ПОМОЩЬЮ ConcatAdapter
        ConcatAdapter concatAdapter = new ConcatAdapter(headerAdapter, appsAdapter, footerAdapter);
        recyclerView.setAdapter(concatAdapter);

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        // Запускаем расчет данных для нижних карточек
        loadBottomCardsData(activity, textWeek, textMonth, textYear);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded() || activity == null) return;

            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View v, final int position, long id) {
                    
                    Runnable updateUI = () -> {
                        CachedStats cached = statsCache.get(position);
                        if (cached == null || !isAdded()) return;
                        
                        totalTimeText.setText(Utils.formatTime(activity, cached.totalMillis));
                        appsAdapter.updateData(cached.list, cached.times); // Обновляем только средний адаптер!
                    };

                    if (statsCache.containsKey(position)) {
                        updateUI.run();
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
                            
                            switch (position) {
                                case 0: cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); startTime = cal.getTimeInMillis(); interval = -1; break;
                                case 1: cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); endTime = cal.getTimeInMillis(); cal.add(Calendar.DAY_OF_YEAR, -1); startTime = cal.getTimeInMillis(); interval = -1; break;
                                case 2: cal.add(Calendar.DAY_OF_YEAR, -7); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_DAILY; break;
                                case 3: cal.add(Calendar.MONTH, -1); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_WEEKLY; break;
                                default: cal.add(Calendar.YEAR, -1); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_YEARLY; break;
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
                                statsCache.put(position, new CachedStats(finalList, exactTimes, finalTotalMillis));
                                updateUI.run();
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

    private void loadBottomCardsData(Context context, TextView txtWeek, TextView txtMonth, TextView txtYear) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            
            Calendar calW = Calendar.getInstance();
            calW.add(Calendar.DAY_OF_YEAR, -7);
            long weekTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_DAILY, calW.getTimeInMillis(), now));

            Calendar calM = Calendar.getInstance();
            calM.add(Calendar.MONTH, -1);
            long monthTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_WEEKLY, calM.getTimeInMillis(), now));

            Calendar calY = Calendar.getInstance();
            calY.add(Calendar.YEAR, -1);
            long yearTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_YEARLY, calY.getTimeInMillis(), now));

            new Handler(Looper.getMainLooper()).post(() -> {
                if (isAdded()) {
                    txtWeek.setText(Utils.formatTime(context, weekTotal));
                    txtMonth.setText(Utils.formatTime(context, monthTotal));
                    txtYear.setText(Utils.formatTime(context, yearTotal));
                }
            });
        });
    }

    private long filterAndSumUserApps(Context context, Map<String, Long> exactTimes) {
        long total = 0;
        PackageManager pm = context.getPackageManager();
        Set<String> userApps = getUserApps(pm);
        String launcherPkg = getLauncherPackage(pm);
        for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
            String pkg = entry.getKey();
            long time = entry.getValue();
            boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || pkg.equals(launcherPkg);
            if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) {
                total += time;
            }
        }
        return total;
    }

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

    private Map<String, Long> calculateFromStats(Context context, int interval, long start, long end) {
        Map<String, Long> results = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return results;

        List<UsageStats> stats = usm.queryUsageStats(interval, start, end);
        if (stats != null) {
            for (UsageStats s : stats) {
                long time = s.getTotalTimeInForeground();
                if (time > 0) {
                    if (interval == UsageStatsManager.INTERVAL_YEARLY) {
                        results.put(s.getPackageName(), Math.max(results.getOrDefault(s.getPackageName(), 0L), time));
                    } else {
                        results.put(s.getPackageName(), results.getOrDefault(s.getPackageName(), 0L) + time);
                    }
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

    // --- УНИВЕРСАЛЬНЫЙ АДАПТЕР ДЛЯ ШАПКИ И ПОДВАЛА ---
    private static class SingleViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View view;

        public SingleViewAdapter(View view) {
            this.view = view;
        }

        @Override
        public int getItemCount() {
            return 1;
        }

        @Override
        public int getItemViewType(int position) {
            return view.hashCode(); // Гарантирует уникальность типа для шапки и подвала
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Задаем правильные параметры Layout, чтобы элемент растянулся по ширине
            RecyclerView.LayoutParams layoutParams = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            view.setLayoutParams(layoutParams);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            // Ничего делать не нужно, так как данные мы обновляем напрямую во View
        }
    }
}
