package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import com.myonlinetime.app.utils.UsageMath;
import com.myonlinetime.app.utils.Utils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    @Override
    public void onResume() {
        super.onResume();
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
        mainValTxt.setText(getString(R.string.format_days_hours, 0, 0));
        subValTxt.setText(getString(R.string.format_total_hours_mins, 0, 0));
        yesterdayValTxt.setText(getString(R.string.format_plus_hours_mins, 0, 0));

        Utils.backgroundExecutor.execute(() -> {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || !isAdded()) return;

            long startDate = prefs.getLong(KEY_START_DATE, 0);
            long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
            long historicalTotalMillis = prefs.getLong(KEY_TOTAL_TIME, 0);
            Map<String, Long> historicalAppsMapRaw = loadAppsFromCache();
            
            // ЖЕСТКАЯ ОЧИСТКА КЭША ОТ ДУБЛИКАТОВ
            Map<String, Long> historicalAppsMap = new HashMap<>();
            for (Map.Entry<String, Long> entry : historicalAppsMapRaw.entrySet()) {
                String cleanPkg = entry.getKey().trim();
                Long current = historicalAppsMap.get(cleanPkg);
                historicalAppsMap.put(cleanPkg, (current == null ? 0L : current) + entry.getValue());
            }

            if (UsageMath.todayStartMillis == 0) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                UsageMath.todayStartMillis = cal.getTimeInMillis();
                Calendar yCal = (Calendar) cal.clone();
                yCal.add(Calendar.DAY_OF_YEAR, -1);
                UsageMath.yesterdayStartMillis = yCal.getTimeInMillis();
            }

            long todayStartMillis = UsageMath.todayStartMillis;

            // 1. ОБНОВЛЕНИЕ ИСТОРИЧЕСКОГО КЭША
            if (startDate == 0) {
                Calendar oneYearAgo = Calendar.getInstance();
                oneYearAgo.add(Calendar.YEAR, -1);
                
                historicalAppsMap = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_YEARLY, oneYearAgo.getTimeInMillis(), todayStartMillis);
                historicalTotalMillis = UsageMath.sumMap(historicalAppsMap);

                UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
                List<UsageStats> yearlyStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, oneYearAgo.getTimeInMillis(), System.currentTimeMillis());
                long earliestStart = System.currentTimeMillis();
                if (yearlyStats != null) {
                    for (UsageStats stat : yearlyStats) {
                        if (stat.getFirstTimeStamp() > 0 && stat.getFirstTimeStamp() < earliestStart) {
                            earliestStart = stat.getFirstTimeStamp();
                        }
                    }
                }
                startDate = earliestStart;
                lastUpdate = todayStartMillis;
                saveToCache(startDate, lastUpdate, historicalTotalMillis, historicalAppsMap);
                
            } else if (lastUpdate < todayStartMillis) {
                Map<String, Long> gapTimes = UsageMath.getFilteredExactTimes(activity, lastUpdate, todayStartMillis);
                for (Map.Entry<String, Long> entry : gapTimes.entrySet()) {
                    String pkg = entry.getKey().trim();
                    long time = entry.getValue();
                    Long current = historicalAppsMap.get(pkg);
                    historicalAppsMap.put(pkg, (current == null ? 0L : current) + time);
                }
                historicalTotalMillis = UsageMath.sumMap(historicalAppsMap);
                lastUpdate = todayStartMillis;
                saveToCache(startDate, lastUpdate, historicalTotalMillis, historicalAppsMap);
            }

            // 2. БЕРЕМ ГОТОВЫЕ ДАННЫЕ ЗА СЕГОДНЯ И ВЧЕРА
            Map<String, Long> todayTimes = UsageMath.todayExactCache != null ? 
                                           UsageMath.todayExactCache : 
                                           UsageMath.getFilteredExactTimes(activity, todayStartMillis, System.currentTimeMillis());

            Map<String, Long> yesterdayTimes = UsageMath.yesterdayExactCache != null ? 
                                               UsageMath.yesterdayExactCache : 
                                               UsageMath.getFilteredExactTimes(activity, UsageMath.yesterdayStartMillis, todayStartMillis);
            
            long yesterdayTotal = UsageMath.sumMap(yesterdayTimes);

            // 3. ФИКС БАГА С ДУБЛИКАТАМИ: Сливаем исторический кэш с данными ЗА СЕГОДНЯ
            Map<String, Long> finalAppsMap = new HashMap<>(historicalAppsMap);
            for (Map.Entry<String, Long> entry : todayTimes.entrySet()) {
                String pkg = entry.getKey().trim(); // Обрезаем пробелы для 100% слияния
                Long current = finalAppsMap.get(pkg);
                finalAppsMap.put(pkg, (current == null ? 0L : current) + entry.getValue());
            }

            long finalTotalMillis = UsageMath.sumMap(finalAppsMap);
            final long finalStartDate = startDate;
            final long finalYesterdayTotal = yesterdayTotal;

            List<String> sortedApps = new ArrayList<>(finalAppsMap.keySet());
            Collections.sort(sortedApps, (left, right) -> Long.compare(finalAppsMap.get(right), finalAppsMap.get(left)));

            // =========================================================================
            // ТОТАЛЬНАЯ ПРЕДЗАГРУЗКА БЕЗ ЛИМИТОВ
            // =========================================================================
            PackageManager pm = activity.getPackageManager();
            for (String pkgName : sortedApps) {
                try {
                    int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                               PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                    
                    android.content.pm.ApplicationInfo info;
                    try {
                        info = pm.getApplicationInfo(pkgName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        info = pm.getApplicationInfo(pkgName, flag);
                    }
                    
                    pm.getApplicationLabel(info); 
                    pm.getApplicationIcon(info);  
                } catch (Exception ignored) { }
            }

            // 4. ВОЗВРАЩАЕМСЯ В ГЛАВНЫЙ ПОТОК ДЛЯ ОТРИСОВКИ
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                
                adapter.updateData(sortedApps, finalAppsMap);
                
                cachedTotalMillis = finalTotalMillis;
                cachedYesterdayTotal = finalYesterdayTotal;
                cachedStartDate = finalStartDate;
                isDataReady = true;

                if (isResumed() && !isAnimated) {
                    runNumbersAnimation();
                }
            });
        });
    }

    private void runNumbersAnimation() {
        isAnimated = true; 

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
            // Очищаем ключи перед сохранением в кэш
            for (Map.Entry<String, Long> entry : appsMap.entrySet()) {
                json.put(entry.getKey().trim(), entry.getValue());
            }
            prefs.edit()
                .putLong(KEY_START_DATE, startDate)
                .putLong(KEY_LAST_UPDATE, lastUpdate)
                .putLong(KEY_TOTAL_TIME, totalTime)
                .putString(KEY_APPS_JSON, json.toString())
                .apply();
        } catch (Exception e) { e.printStackTrace(); }
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
