package com.myonlinetime.app.ui;

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

public class StatsTimeFragment extends Fragment {

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

        // Запускаем сборщик данных для нижних карточек
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

        // Грузим первый экран
        fetchAndApplyData(0, activity);

        return view;
    }

    // =========================================================================
    // УМНЫЙ UI ДИСПЕТЧЕР (Связь с Глобальным Кэшем UsageMath)
    // =========================================================================
    private void fetchAndApplyData(int position, MainActivity activity) {
        Runnable updateUI = () -> {
            UsageMath.AppStatsResult cached = UsageMath.globalTimeCache.get(position);
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

        // 1. Идеальный сценарий: данные уже в глобальном кэше
        if (UsageMath.globalTimeCache.containsKey(position)) {
            updateUI.run();
            return; 
        }

        if (!isAdded()) return;
        totalTimeText.setText(activity.getString(R.string.loading));

        // 2. Данные сейчас считаются в глобальном пуле?
        if (Boolean.TRUE.equals(UsageMath.isCalculating.get(position))) {
            pollCache(position, updateUI);
        } else {
            // 3. Страховка: если по какой-то причине MainActivity не запустила прогрев - пинаем вручную
            UsageMath.preloadAbsoluteEverything(activity);
            pollCache(position, updateUI);
        }
    }

    // Пинговальщик для "Режима Ждуна"
    private void pollCache(int position, Runnable updateUI) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) return;
            if (UsageMath.globalTimeCache.containsKey(position)) {
                updateUI.run(); 
            } else {
                pollCache(position, updateUI); 
            }
        }, 150);
    }

    // =========================================================================
    // ОТРИСОВКА НИЖНИХ КАРТОЧЕК (Тоже берут из Глобального Кэша)
    // =========================================================================
    private void loadBottomCardsData(Context context, TextView txtWeek, TextView txtMonth, TextView txtYear) {
        Runnable updateCards = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                
                // Проверяем индексы: 2-Неделя, 3-Месяц, 4-Год
                UsageMath.AppStatsResult weekData = UsageMath.globalTimeCache.get(2);
                UsageMath.AppStatsResult monthData = UsageMath.globalTimeCache.get(3);
                UsageMath.AppStatsResult yearData = UsageMath.globalTimeCache.get(4);

                if (weekData != null && monthData != null && yearData != null) {
                    // Все три периода досчитались в фоне! Выводим на экран.
                    txtWeek.setText(Utils.formatTime(context, weekData.totalMillis));
                    txtMonth.setText(Utils.formatTime(context, monthData.totalMillis));
                    txtYear.setText(Utils.formatTime(context, yearData.totalMillis));
                } else {
                    // Какая-то часть еще в процессе расчета (скорее всего Год). Ждем еще 300мс.
                    new Handler(Looper.getMainLooper()).postDelayed(this, 300);
                }
            }
        };
        
        // Запускаем первую проверку
        updateCards.run();
    }
            }
                
