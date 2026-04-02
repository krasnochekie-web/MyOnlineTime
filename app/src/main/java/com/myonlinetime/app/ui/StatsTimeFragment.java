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

public class StatsTimeFragment extends Fragment {

    private static class CachedStats {
        List<String> list; Map<String, Long> times; long totalMillis;
        CachedStats(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }
    
    // ВЕЧНЫЙ КЭШ: Данные сохраняются до полного закрытия (выгрузки) приложения из памяти
    private static final Map<Integer, CachedStats> statsCache = new HashMap<>();
    private static long cachedWeek = -1;
    private static long cachedMonth = -1;
    private static long cachedYear = -1;
    
    public StatsTimeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_time_tab, container, false); 
        
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        final androidx.core.widget.NestedScrollView scrollView = view.findViewById(R.id.scroll_view_time);
        final RecyclerView recyclerView = view.findViewById(R.id.apps_list);
        final Spinner spinner = view.findViewById(R.id.spinner_period);
        final TextView totalTimeText = view.findViewById(R.id.text_total_time_sum);
        
        final View dividerShowMore = view.findViewById(R.id.divider_show_more);
        final TextView btnShowMore = view.findViewById(R.id.btn_show_more);

        final TextView textWeek = view.findViewById(R.id.text_time_week);
        final TextView textMonth = view.findViewById(R.id.text_time_month);
        final TextView textYear = view.findViewById(R.id.text_time_year);

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        
        final AppsAdapter adapter = new AppsAdapter(activity, R.layout.item_app_usage_time, true);
        recyclerView.setAdapter(adapter);

        btnShowMore.setOnClickListener(v -> {
            if (adapter.isFullyExpanded()) {
                adapter.collapse();
                btnShowMore.setText(R.string.show_more);
                if (scrollView != null) scrollView.smoothScrollTo(0, 0); 
            } else {
                boolean reachedEnd = adapter.loadMoreChunk();
                if (reachedEnd) {
                    btnShowMore.setText(R.string.show_less);
                } else {
                    btnShowMore.setVisibility(View.GONE); 
                    dividerShowMore.setVisibility(View.GONE);
                }
            }
        });

        if (scrollView != null) {
            scrollView.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) 
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (adapter.hasStartedExpanding() && !adapter.isFullyExpanded()) {
                    if (scrollY >= (v.getChildAt(0).getMeasuredHeight() - v.getMeasuredHeight() - 200)) {
                        boolean reachedEnd = adapter.loadMoreChunk();
                        if (reachedEnd) {
                            btnShowMore.setText(R.string.show_less);
                            btnShowMore.setVisibility(View.VISIBLE);
                            dividerShowMore.setVisibility(View.VISIBLE);
                        }
                    }
                }
            });
        }

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

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
                        adapter.updateData(cached.list, cached.times);
                        
                        adapter.collapse();
                        btnShowMore.setText(R.string.show_more);
                        if (cached.list.size() > 3) {
                            btnShowMore.setVisibility(View.VISIBLE);
                            dividerShowMore.setVisibility(View.VISIBLE);
                        } else {
                            btnShowMore.setVisibility(View.GONE);
                            dividerShowMore.setVisibility(View.GONE);
                        }
                    };

                    if (statsCache.containsKey(position)) {
                        updateUI.run();
                        return; // Загружено из статического кэша мгновенно
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isAdded()) return;
                        totalTimeText.setText(activity.getString(R.string.loading));
                        
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            Calendar cal = Calendar.getInstance(); 
                            long endTime = System.currentTimeMillis();
                            long startTime; int interval;
                            
                            switch (position) {
                                case 0: cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); startTime = cal.getTimeInMillis(); interval = -1; break;
                                case 1: cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); endTime = cal.getTimeInMillis(); cal.add(Calendar.DAY_OF_YEAR, -1); startTime = cal.getTimeInMillis(); interval = -1; break;
                                case 2: cal.add(Calendar.DAY_OF_YEAR, -7); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_DAILY; break;
                                case 3: cal.add(Calendar.MONTH, -1); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_WEEKLY; break;
                                default: cal.add(Calendar.YEAR, -1); startTime = cal.getTimeInMillis(); interval = UsageStatsManager.INTERVAL_YEARLY; break;
                            }
                            
                            final Map<String, Long> exactTimes = (interval == -1) ? calculateFromEvents(activity, startTime, endTime) : calculateFromStats(activity, interval, startTime, endTime);
                            
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
            if (spinner.getSelectedItemPosition() >= 0) spinner.getOnItemSelectedListener().onItemSelected(spinner, null, spinner.getSelectedItemPosition(), 0);
        }, 300);
        return view;
    }

    private void loadBottomCardsData(Context context, TextView txtWeek, TextView txtMonth, TextView txtYear) {
        // КЭШ НИЖНИХ КАРТОЧЕК: Если данные уже есть, вставляем их мгновенно
        if (cachedWeek != -1) {
            txtWeek.setText(Utils.formatTime(context, cachedWeek));
            txtMonth.setText(Utils.formatTime(context, cachedMonth));
            txtYear.setText(Utils.formatTime(context, cachedYear));
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar calW = Calendar.getInstance(); calW.add(Calendar.DAY_OF_YEAR, -7);
            long weekTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_DAILY, calW.getTimeInMillis(), now));

            Calendar calM = Calendar.getInstance(); calM.add(Calendar.MONTH, -1);
            long monthTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_WEEKLY, calM.getTimeInMillis(), now));

            Calendar calY = Calendar.getInstance(); calY.add(Calendar.YEAR, -1);
            long yearTotal = filterAndSumUserApps(context, calculateFromStats(context, UsageStatsManager.INTERVAL_YEARLY, calY.getTimeInMillis(), now));

            cachedWeek = weekTotal;
            cachedMonth = monthTotal;
            cachedYear = yearTotal;

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
            if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) total += time;
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
                    if (interval == UsageStatsManager.INTERVAL_YEARLY) results.put(s.getPackageName(), Math.max(results.getOrDefault(s.getPackageName(), 0L), time));
                    else results.put(s.getPackageName(), results.getOrDefault(s.getPackageName(), 0L) + time);
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
        for (android.content.pm.ResolveInfo info : resolvedInfos) apps.add(info.activityInfo.packageName);
        return apps;
    }
    
    private String getLauncherPackage(PackageManager pm) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
    }

    // ========================================================
    // ВОТ ОН - МЕТОД onResume ДЛЯ ВЫКЛЮЧЕНИЯ ФОНА!
    // ========================================================
    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }
    // ========================================================

} // <-- Конец класса StatsTimeFragment
