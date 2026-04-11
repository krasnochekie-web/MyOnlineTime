package com.myonlinetime.app.ui;

import android.app.usage.UsageStatsManager;
import android.content.Context;
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
import com.myonlinetime.app.utils.UsageMath;
import com.myonlinetime.app.utils.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatsTimeFragment extends Fragment {

    private static class CachedStats {
        List<String> list; Map<String, Long> times; long totalMillis;
        CachedStats(List<String> l, Map<String, Long> t, long tm) {
            this.list = l; this.times = t; this.totalMillis = tm;
        }
    }
    
    // 1. ИСПОЛЬЗУЕМ CONCURRENT HASH MAP ДЛЯ ПОТОКОБЕЗОПАСНОСТИ
    private static final Map<Integer, CachedStats> statsCache = new ConcurrentHashMap<>();
    private static long cachedWeek = -1;
    private static long cachedMonth = -1;
    private static long cachedYear = -1;
    
    private TextView totalTimeText;
    private AppsAdapter adapter;
    private View listFooterCard;
    private View dividerShowMore;
    private TextView btnShowMore;
    
    private boolean isFirstLoad = true; 

    public StatsTimeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_time_tab, container, false); 
        
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();
        }

        final RecyclerView recyclerView = view.findViewById(R.id.apps_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setItemViewCacheSize(25);
        
        final View headerView = inflater.inflate(R.layout.layout_time_header, recyclerView, false);
        final View footerView = inflater.inflate(R.layout.layout_time_footer, recyclerView, false);
        
        final Spinner spinner = headerView.findViewById(R.id.spinner_period);
        totalTimeText = headerView.findViewById(R.id.text_total_time_sum);
        
        listFooterCard = footerView.findViewById(R.id.list_footer_card);
        dividerShowMore = footerView.findViewById(R.id.divider_show_more);
        btnShowMore = footerView.findViewById(R.id.btn_show_more);
        
        final TextView textWeek = footerView.findViewById(R.id.text_time_week);
        final TextView textMonth = footerView.findViewById(R.id.text_time_month);
        final TextView textYear = footerView.findViewById(R.id.text_time_year);

        RecyclerView.Adapter<?> headerAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new RecyclerView.ViewHolder(headerView) {}; }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 1; }
        };

        RecyclerView.Adapter<?> footerAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new RecyclerView.ViewHolder(footerView) {}; }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 1; }
        };

        adapter = new AppsAdapter(activity, R.layout.item_app_usage_time, true);
        ConcatAdapter concatAdapter = new ConcatAdapter(headerAdapter, adapter, footerAdapter);
        recyclerView.setAdapter(concatAdapter);

        btnShowMore.setOnClickListener(v -> {
            if (adapter.isFullyExpanded()) {
                adapter.collapse();
                btnShowMore.setText(R.string.show_more);
                recyclerView.smoothScrollToPosition(0); 
            } else {
                boolean reachedEnd = adapter.loadMoreChunk();
                if (reachedEnd) {
                    btnShowMore.setText(R.string.show_less);
                    listFooterCard.setVisibility(View.VISIBLE);
                } else {
                    listFooterCard.setVisibility(View.GONE);
                }
            }
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (adapter.hasStartedExpanding() && !adapter.isFullyExpanded()) {
                    if (!rv.canScrollVertically(1)) { 
                        boolean reachedEnd = adapter.loadMoreChunk();
                        if (reachedEnd) {
                            btnShowMore.setText(R.string.show_less);
                            listFooterCard.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        });

        totalTimeText.setText(getString(R.string.loading));

        String[] periods = getResources().getStringArray(R.array.periods_array);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item, periods);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        loadBottomCardsData(activity, textWeek, textMonth, textYear);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, final int position, long id) {
                if (isFirstLoad) {
                    isFirstLoad = false;
                    return; 
                }
                fetchAndApplyData(position, activity);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        }); 

        fetchAndApplyData(0, activity);

        return view;
    }

    private void fetchAndApplyData(int position, MainActivity activity) {
        Runnable updateUI = () -> {
            CachedStats cached = statsCache.get(position);
            if (cached == null || !isAdded()) return;
            
            totalTimeText.setText(Utils.formatTime(activity, cached.totalMillis));
            adapter.updateData(cached.list, cached.times);
            
            adapter.collapse();
            btnShowMore.setText(R.string.show_more);
            
            if (cached.list.size() > 3) {
                listFooterCard.setVisibility(View.VISIBLE);
                btnShowMore.setVisibility(View.VISIBLE);
                dividerShowMore.setVisibility(View.VISIBLE);
            } else {
                listFooterCard.setVisibility(View.GONE);
            }
        };

        if (statsCache.containsKey(position)) {
            updateUI.run();
            return; 
        }

        if (!isAdded()) return;
        totalTimeText.setText(activity.getString(R.string.loading));
        
        Utils.backgroundExecutor.execute(() -> {
            if (UsageMath.todayStartMillis == 0) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                UsageMath.todayStartMillis = cal.getTimeInMillis();
                Calendar yCal = (Calendar) cal.clone();
                yCal.add(Calendar.DAY_OF_YEAR, -1);
                UsageMath.yesterdayStartMillis = yCal.getTimeInMillis();
            }

            long endTime = System.currentTimeMillis();
            Map<String, Long> exactTimes = null;
            Calendar cal = Calendar.getInstance(); 
            
            switch (position) {
                case 0: exactTimes = UsageMath.todayExactCache != null ? UsageMath.todayExactCache : UsageMath.getFilteredExactTimes(activity, UsageMath.todayStartMillis, endTime); break;
                case 1: exactTimes = UsageMath.yesterdayExactCache != null ? UsageMath.yesterdayExactCache : UsageMath.getFilteredExactTimes(activity, UsageMath.yesterdayStartMillis, UsageMath.todayStartMillis); break;
                case 2: cal.add(Calendar.DAY_OF_YEAR, -7); exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), endTime); break;
                case 3: cal.add(Calendar.MONTH, -1); exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_WEEKLY, cal.getTimeInMillis(), endTime); break;
                default: cal.add(Calendar.YEAR, -1); exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_YEARLY, cal.getTimeInMillis(), endTime); break;
            }

            final Map<String, Long> finalExactTimes = exactTimes;
            final List<String> finalList = new ArrayList<>(finalExactTimes.keySet());
            
            Collections.sort(finalList, (left, right) -> Long.compare(finalExactTimes.get(right), finalExactTimes.get(left)));
            final long finalTotalMillis = UsageMath.sumMap(finalExactTimes);
            
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isAdded()) {
                    statsCache.put(position, new CachedStats(finalList, finalExactTimes, finalTotalMillis));
                    updateUI.run();
                }
            });
        }); 
    }

    private void loadBottomCardsData(Context context, TextView txtWeek, TextView txtMonth, TextView txtYear) {
        if (cachedWeek != -1) {
            txtWeek.setText(Utils.formatTime(context, cachedWeek));
            txtMonth.setText(Utils.formatTime(context, cachedMonth));
            txtYear.setText(Utils.formatTime(context, cachedYear));
            return;
        }

        Utils.backgroundExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            
            Calendar calW = Calendar.getInstance(); calW.add(Calendar.DAY_OF_YEAR, -7);
            long weekTotal = UsageMath.sumMap(UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_DAILY, calW.getTimeInMillis(), now));

            Calendar calM = Calendar.getInstance(); calM.add(Calendar.MONTH, -1);
            long monthTotal = UsageMath.sumMap(UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_WEEKLY, calM.getTimeInMillis(), now));

            Calendar calY = Calendar.getInstance(); calY.add(Calendar.YEAR, -1);
            long yearTotal = UsageMath.sumMap(UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_YEARLY, calY.getTimeInMillis(), now));

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

    // =========================================================================
    // 2. ТИХИЙ ПРОГРЕВ КЭША ДЛЯ ВСЕХ ПЕРИОДОВ
    // =========================================================================
    public static void preloadAllCachesQuietly(Context context) {
        Utils.backgroundExecutor.execute(() -> {
            for (int position = 0; position <= 4; position++) {
                if (statsCache.containsKey(position)) continue; 

                long endTime = System.currentTimeMillis();
                Map<String, Long> exactTimes = null;
                Calendar cal = Calendar.getInstance(); 
                
                if (UsageMath.todayStartMillis == 0) {
                    Calendar tCal = Calendar.getInstance();
                    tCal.set(Calendar.HOUR_OF_DAY, 0); tCal.set(Calendar.MINUTE, 0); tCal.set(Calendar.SECOND, 0); tCal.set(Calendar.MILLISECOND, 0);
                    UsageMath.todayStartMillis = tCal.getTimeInMillis();
                    Calendar yCal = (Calendar) tCal.clone();
                    yCal.add(Calendar.DAY_OF_YEAR, -1);
                    UsageMath.yesterdayStartMillis = yCal.getTimeInMillis();
                }

                switch (position) {
                    case 0: exactTimes = UsageMath.todayExactCache != null ? UsageMath.todayExactCache : UsageMath.getFilteredExactTimes(context, UsageMath.todayStartMillis, endTime); break;
                    case 1: exactTimes = UsageMath.yesterdayExactCache != null ? UsageMath.yesterdayExactCache : UsageMath.getFilteredExactTimes(context, UsageMath.yesterdayStartMillis, UsageMath.todayStartMillis); break;
                    case 2: cal.add(Calendar.DAY_OF_YEAR, -7); exactTimes = UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), endTime); break;
                    case 3: cal.add(Calendar.MONTH, -1); exactTimes = UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_WEEKLY, cal.getTimeInMillis(), endTime); break;
                    default: cal.add(Calendar.YEAR, -1); exactTimes = UsageMath.getFilteredStats(context, UsageStatsManager.INTERVAL_YEARLY, cal.getTimeInMillis(), endTime); break;
                }

                final Map<String, Long> finalExactTimes = exactTimes;
                final List<String> finalList = new ArrayList<>(finalExactTimes.keySet());
                
                Collections.sort(finalList, (left, right) -> Long.compare(finalExactTimes.get(right), finalExactTimes.get(left)));
                final long finalTotalMillis = UsageMath.sumMap(finalExactTimes);
                
                statsCache.put(position, new CachedStats(finalList, finalExactTimes, finalTotalMillis));
            }
        });
    }
            }
            
