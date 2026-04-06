package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.adapters.AppsAdapter;
import com.myonlinetime.app.utils.Utils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AllTimeFragment extends Fragment {

    private TextView mainValTxt, subValTxt, descTxt, yesterdayValTxt;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;
    private SharedPreferences prefs;

    private static final String PREF_NAME = "AllTimeStatsCache";
    private static final String KEY_START_DATE = "start_date_millis";
    private static final String KEY_LAST_UPDATE = "last_update_millis";
    private static final String KEY_TOTAL_TIME = "total_time_millis";
    private static final String KEY_APPS_JSON = "apps_data_json";

    // =========================================================================
    // ПЕРЕМЕННЫЕ ДЛЯ УМНОЙ АНИМАЦИИ
    // =========================================================================
    private boolean isDataReady = false; 
    private boolean isAnimated = false; 
    
    private long cachedTotalMillis = 0;
    private long cachedYesterdayTotal = 0;
    private long cachedStartDate = 0;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_all_time, container, false);
        final MainActivity activity = (MainActivity) getActivity();

        if (activity != null) {
            prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }

        mainValTxt = view.findViewById(R.id.all_time_main_val);
        subValTxt = view.findViewById(R.id.all_time_sub_val);
        descTxt = view.findViewById(R.id.all_time_desc);
        yesterdayValTxt = view.findViewById(R.id.all_time_yesterday_val);
        
        View headerWrapper = view.findViewById(R.id.yesterday_banner_wrapper);
        ViewGroup parent = (ViewGroup) headerWrapper.getParent();
        if (parent != null) {
            parent.removeView(headerWrapper);
        }

        View howItWorksBtn = view.findViewById(R.id.how_it_works_btn);
        if (howItWorksBtn != null) {
            howItWorksBtn.setOnClickListener(v -> showHowItWorksDialog(true));
        }

        recyclerView = view.findViewById(R.id.all_time_apps_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        
        adapter = new AppsAdapter(activity, R.layout.item_app_usage_time, false);
        
        HeaderWrapperAdapter wrapperAdapter = new HeaderWrapperAdapter(headerWrapper, adapter);
        recyclerView.setAdapter(wrapperAdapter);

        loadAndCalculateStats();

        return view;
    }

    // =========================================================================
    // СПУСКОВОЙ КРЮЧОК АНИМАЦИИ (Срабатывает только когда вкладка видима)
    // =========================================================================
    @Override
    public void onResume() {
        super.onResume();
        // Если пользователь перелистнул на эту вкладку, а данные уже были посчитаны в фоне:
        if (isDataReady && !isAnimated) {
            runNumbersAnimation();
        }
    }

    private void showHowItWorksDialog(boolean isAllTime) {
        final Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_how_it_works);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        }

        TextView descText = dialog.findViewById(R.id.dialog_description_text);
        Button btnOk = dialog.findViewById(R.id.dialog_ok_btn);

        if (isAllTime) {
            descText.setText(getString(R.string.dialog_how_it_works_all_time));
        } else {
            descText.setText(getString(R.string.dialog_how_it_works_charts));
        }

        btnOk.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
    
    private void loadAndCalculateStats() {
        // Ставим "нули" на время загрузки
        mainValTxt.setText(getString(R.string.format_days_hours, 0, 0));
        subValTxt.setText(getString(R.string.format_total_hours_mins, 0, 0));
        yesterdayValTxt.setText(getString(R.string.format_plus_hours_mins, 0, 0));

        // ИСПОЛЬЗУЕМ НАШ ГЛОБАЛЬНЫЙ ПУЛ ПОТОКОВ (Без утечек и с низким приоритетом)
        Utils.backgroundExecutor.execute(() -> {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || !isAdded()) return;

            long startDate = prefs.getLong(KEY_START_DATE, 0);
            long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
            long totalMillis = prefs.getLong(KEY_TOTAL_TIME, 0);
            Map<String, Long> appsMap = loadAppsFromCache();

            Calendar todayStart = Calendar.getInstance();
            todayStart.set(Calendar.HOUR_OF_DAY, 0);
            todayStart.set(Calendar.MINUTE, 0);
            todayStart.set(Calendar.SECOND, 0);
            todayStart.set(Calendar.MILLISECOND, 0);
            long todayStartMillis = todayStart.getTimeInMillis();

            Calendar yesterdayStart = (Calendar) todayStart.clone();
            yesterdayStart.add(Calendar.DAY_OF_YEAR, -1);
            long yesterdayStartMillis = yesterdayStart.getTimeInMillis();

            if (startDate == 0) {
                Calendar oneYearAgo = Calendar.getInstance();
                oneYearAgo.add(Calendar.YEAR, -1);
                
                UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
                List<UsageStats> yearlyStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, oneYearAgo.getTimeInMillis(), System.currentTimeMillis());
                
                long earliestStart = System.currentTimeMillis();
                appsMap.clear();
                totalMillis = 0;

                Set<String> userApps = getUserApps(activity.getPackageManager());
                String launcherPkg = getLauncherPackage(activity.getPackageManager());

                if (yearlyStats != null) {
                    for (UsageStats stat : yearlyStats) {
                        if (stat.getFirstTimeStamp() > 0 && stat.getFirstTimeStamp() < earliestStart) {
                            earliestStart = stat.getFirstTimeStamp();
                        }
                        
                        String pkg = stat.getPackageName();
                        long time = stat.getTotalTimeInForeground();
                        boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                                pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                                pkg.equals(launcherPkg);
                                                
                        if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) {
                            Long current = appsMap.get(pkg);
                            appsMap.put(pkg, Math.max((current == null ? 0L : current), time));
                        }
                    }
                }
                
                for (long t : appsMap.values()) totalMillis += t;
                startDate = earliestStart;
                lastUpdate = todayStartMillis;
                saveToCache(startDate, lastUpdate, totalMillis, appsMap);
            } 
            else if (lastUpdate < todayStartMillis) {
                Map<String, Long> gapTimes = calculateExactTimes(activity, lastUpdate, todayStartMillis);
                
                Set<String> userApps = getUserApps(activity.getPackageManager());
                String launcherPkg = getLauncherPackage(activity.getPackageManager());

                for (Map.Entry<String, Long> entry : gapTimes.entrySet()) {
                    String pkg = entry.getKey();
                    long time = entry.getValue();
                    boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                            pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                            pkg.equals(launcherPkg);
                                            
                    if (time > 1000 && userApps.contains(pkg) && !isSystemTrash) {
                        Long current = appsMap.get(pkg);
                        appsMap.put(pkg, (current == null ? 0L : current) + time);
                        totalMillis += time;
                    }
                }
                lastUpdate = todayStartMillis;
                saveToCache(startDate, lastUpdate, totalMillis, appsMap);
            }

            Map<String, Long> yesterdayTimes = calculateExactTimes(activity, yesterdayStartMillis, todayStartMillis);
            long yesterdayTotal = 0;
            Set<String> uApps = getUserApps(activity.getPackageManager());
            String lPkg = getLauncherPackage(activity.getPackageManager());
            for (Map.Entry<String, Long> entry : yesterdayTimes.entrySet()) {
                if (entry.getValue() > 1000 && uApps.contains(entry.getKey()) && !entry.getKey().equals(lPkg) && !entry.getKey().equals("com.android.systemui")) {
                    yesterdayTotal += entry.getValue();
                }
            }

            List<String> sortedApps = new ArrayList<>(appsMap.keySet());
            Collections.sort(sortedApps, (left, right) -> Long.compare(appsMap.get(right), appsMap.get(left)));

            final long finalTotalMillis = totalMillis;
            final long finalYesterdayTotal = yesterdayTotal;
            final long finalStartDate = startDate;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                
                adapter.updateData(sortedApps, appsMap);
                
                // Сохраняем готовые цифры
                cachedTotalMillis = finalTotalMillis;
                cachedYesterdayTotal = finalYesterdayTotal;
                cachedStartDate = finalStartDate;
                isDataReady = true;

                // Если загрузка закончилась и мы прямо сейчас смотрим на экран - запускаем анимацию
                if (isResumed() && !isAnimated) {
                    runNumbersAnimation();
                }
            });
        });
    }

    // =========================================================================
    // ЛОГИКА САМОЙ АНИМАЦИИ (Вынесена в отдельный метод)
    // =========================================================================
    private void runNumbersAnimation() {
        isAnimated = true; // Запоминаем, что мы уже проиграли анимацию

        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
        String dateStr = sdf.format(cachedStartDate);
        descTxt.setText(getString(R.string.text_all_time_desc, dateStr));

        long yHours = cachedYesterdayTotal / (1000 * 60 * 60);
        long yMins = (cachedYesterdayTotal / (1000 * 60)) % 60;
        yesterdayValTxt.setText(getString(R.string.format_plus_hours_mins, yHours, yMins));

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200); 
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            long currentMillis = (long) (cachedTotalMillis * fraction);

            long totalHoursAll = currentMillis / (1000 * 60 * 60);
            long totalMinsAll = (currentMillis / (1000 * 60)) % 60;
            
            long days = currentMillis / (1000 * 60 * 60 * 24);
            long hoursRemain = (currentMillis / (1000 * 60 * 60)) % 24;

            mainValTxt.setText(getString(R.string.format_days_hours, days, hoursRemain));
            subValTxt.setText(getString(R.string.format_total_hours_mins, totalHoursAll, totalMinsAll));
        });
        animator.start();
    }

    private Map<String, Long> loadAppsFromCache() {
        Map<String, Long> map = new HashMap<>();
        String jsonStr = prefs.getString(KEY_APPS_JSON, "{}");
        try {
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.getLong(key));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return map;
    }

    private void saveToCache(long startDate, long lastUpdate, long totalTime, Map<String, Long> appsMap) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Long> entry : appsMap.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            prefs.edit()
                .putLong(KEY_START_DATE, startDate)
                .putLong(KEY_LAST_UPDATE, lastUpdate)
                .putLong(KEY_TOTAL_TIME, totalTime)
                .putString(KEY_APPS_JSON, json.toString())
                .apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private Map<String, Long> calculateExactTimes(Context context, long start, long end) {
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
                    if (duration > 0) {
                        Long current = results.get(pkg);
                        results.put(pkg, (current == null ? 0L : current) + duration);
                    }
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

    private class HeaderWrapperAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View headerView;
        private final RecyclerView.Adapter innerAdapter;

        public HeaderWrapperAdapter(View headerView, RecyclerView.Adapter innerAdapter) {
            this.headerView = headerView;
            this.innerAdapter = innerAdapter;

            this.innerAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { notifyDataSetChanged(); }
                @Override public void onItemRangeChanged(int positionStart, int itemCount) { notifyItemRangeChanged(positionStart + 1, itemCount); }
                @Override public void onItemRangeInserted(int positionStart, int itemCount) { notifyItemRangeInserted(positionStart + 1, itemCount); }
                @Override public void onItemRangeRemoved(int positionStart, int itemCount) { notifyItemRangeRemoved(positionStart + 1, itemCount); }
                @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) { notifyDataSetChanged(); }
            });
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return 99999;
            return innerAdapter.getItemViewType(position - 1);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 99999) {
                headerView.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(headerView) {};
            }
            return innerAdapter.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position > 0) {
                innerAdapter.onBindViewHolder(holder, position - 1);
            }
        }

        @Override
        public int getItemCount() {
            return innerAdapter.getItemCount() + 1;
        }
    }
}
