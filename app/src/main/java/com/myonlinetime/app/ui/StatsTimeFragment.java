package com.myonlinetime.app.ui;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            activity.headerManager.resetHeader();
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
        
        // === ОПТИМИЗАЦИЯ RECYCLERVIEW (Уровень 1) ===
        recyclerView.setItemViewCacheSize(25); 
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        // ==========================================
        
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
                        return; 
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                                case 0: 
                                    exactTimes = UsageMath.todayExactCache != null ? 
                                                 UsageMath.todayExactCache : 
                                                 UsageMath.getFilteredExactTimes(activity, UsageMath.todayStartMillis, endTime);
                                    break;
                                case 1: 
                                    exactTimes = UsageMath.yesterdayExactCache != null ? 
                                                 UsageMath.yesterdayExactCache : 
                                                 UsageMath.getFilteredExactTimes(activity, UsageMath.yesterdayStartMillis, UsageMath.todayStartMillis);
                                    break;
                                case 2: 
                                    cal.add(Calendar.DAY_OF_YEAR, -7);
                                    exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), endTime);
                                    break;
                                case 3: 
                                    cal.add(Calendar.MONTH, -1);
                                    exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_WEEKLY, cal.getTimeInMillis(), endTime);
                                    break;
                                default: 
                                    cal.add(Calendar.YEAR, -1);
                                    exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_YEARLY, cal.getTimeInMillis(), endTime);
                                    break;
                            }

                            final Map<String, Long> finalExactTimes = exactTimes;
                            final List<String> finalList = new ArrayList<>(finalExactTimes.keySet());
                            
                            Collections.sort(finalList, (left, right) -> Long.compare(finalExactTimes.get(right), finalExactTimes.get(left)));
                            final long finalTotalMillis = UsageMath.sumMap(finalExactTimes);
                            
                            new Handler(Looper.getMainLooper()).post(() -> {
                                statsCache.put(position, new CachedStats(finalList, finalExactTimes, finalTotalMillis));
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

    // === НАСТОЯЩЕЕ АППАРАТНОЕ УСКОРЕНИЕ ДЛЯ ПЛАВНОСТИ АНИМАЦИИ ===
    // Перехватываем саму транзакцию анимации, а не жизненный цикл
    @Nullable
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (nextAnim == 0) {
            return super.onCreateAnimation(transit, enter, nextAnim);
        }

        try {
            Animation anim = AnimationUtils.loadAnimation(requireContext(), nextAnim);
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    // Замораживаем весь UI фрагмента в одну картинку (GPU счастлив)
                    if (getView() != null) {
                        getView().setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    }
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    // Размораживаем UI, чтобы можно было снова скроллить и кликать
                    if (getView() != null) {
                        getView().setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            return anim;
        } catch (Exception e) {
            return super.onCreateAnimation(transit, enter, nextAnim);
        }
    }
    // ===============================================================
}
