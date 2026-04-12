package com.myonlinetime.app.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.adapters.AppsAdapter;
import com.myonlinetime.app.utils.UsageMath;
import com.myonlinetime.app.utils.Utils;

import java.util.Calendar;

public class ChartFragment extends Fragment {

    private WeeklyBarChartView barChart;
    private TextView topDateTxt, topTimeTxt, middleDateTxt;
    private ImageView btnPrev, btnNext;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;

    private int currentIndex = 6; 

    // Переменные для графиков
    private boolean isDataReady = false;
    private boolean isAnimated = false;
    private long[] cachedBarMillis;
    private String[] cachedDayLabels;
    private String[] cachedTopDates;
    private String[] cachedMiddleDates;

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

        View howItWorksBtn = view.findViewById(R.id.how_it_works_btn);
        if (howItWorksBtn != null) {
            howItWorksBtn.setOnClickListener(v -> showHowItWorksDialog(false));
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        
        adapter = new AppsAdapter(activity, R.layout.item_app_usage_time, false);
        recyclerView.setAdapter(adapter);

        barChart.setListener(this::selectDay);

        btnPrev.setOnClickListener(v -> {
            if (currentIndex > 0) selectDay(currentIndex - 1);
        });

        btnNext.setOnClickListener(v -> {
            if (currentIndex < 6) selectDay(currentIndex + 1);
        });

        // Пытаемся загрузить данные из Глобального Кэша
        tryLoadChartData();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isDataReady && !isAnimated) {
            runChartAnimation();
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

    private void selectDay(int index) {
        if (!isDataReady || index < 0 || index > 6) return;
        currentIndex = index;
        
        barChart.setSelectedIndex(index);
        
        // Берем данные за конкретный день прямо из Глобального Кэша!
        UsageMath.AppStatsResult data = UsageMath.globalChartCache[index];

        topDateTxt.setText(cachedTopDates[index]);
        middleDateTxt.setText(cachedMiddleDates[index]);
        topTimeTxt.setText(Utils.formatTime(getContext(), data.totalMillis));
        
        adapter.updateData(data.list, data.times);

        btnPrev.setAlpha(currentIndex == 0 ? 0.3f : 1.0f);
        btnNext.setAlpha(currentIndex == 6 ? 0.3f : 1.0f);
    }

    // =========================================================================
    // ПОДКЛЮЧЕНИЕ К ГЛОБАЛЬНОМУ КЭШУ (Режим Ждуна)
    // =========================================================================
    private void tryLoadChartData() {
        topDateTxt.setText(getString(R.string.loading));

        if (UsageMath.isChartReady) {
            prepareUiData(); // Данные готовы! Форматируем даты и рисуем.
        } else if (UsageMath.isChartCalculating) {
            pollCache(); // Данные в процессе расчета. Ждем.
        } else {
            // Если фоновый расчет не был запущен - запускаем его принудительно (Страховка)
            if (getActivity() != null) {
                UsageMath.preloadAbsoluteEverything(getActivity());
                pollCache();
            }
        }
    }

    private void pollCache() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) return;
            if (UsageMath.isChartReady) {
                prepareUiData();
            } else {
                pollCache();
            }
        }, 150); // Пингуем каждые 150мс
    }

    // =========================================================================
    // ПОДГОТОВКА СТРОК И АНИМАЦИЯ
    // =========================================================================
    private void prepareUiData() {
        String[] daysArray = getResources().getStringArray(R.array.days_short);
        String[] monthsArray = getResources().getStringArray(R.array.months_custom);

        cachedBarMillis = new long[7];
        cachedDayLabels = new String[7];
        cachedTopDates = new String[7];
        cachedMiddleDates = new String[7];

        for (int i = 0; i < 7; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -(6 - i)); 
            
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;
            String dayShort = daysArray[dayOfWeek];
            
            int dayNum = cal.get(Calendar.DAY_OF_MONTH);
            int monthNum = cal.get(Calendar.MONTH);
            String monthStr = monthsArray[monthNum];

            cachedTopDates[i] = getString(R.string.format_date_top, dayShort, dayNum, monthStr);
            cachedMiddleDates[i] = getString(R.string.format_date_middle, dayShort, dayNum, monthStr);
            
            cachedBarMillis[i] = UsageMath.globalChartCache[i].totalMillis;
            cachedDayLabels[i] = dayShort;
        }

        isDataReady = true;

        if (isResumed() && !isAnimated) {
            runChartAnimation();
        }
    }

    private void runChartAnimation() {
        isAnimated = true; 
        barChart.setData(cachedBarMillis, cachedDayLabels);
        selectDay(6); // По умолчанию показываем Сегодня
    }
                                             }

