package com.myonlinetime.app.ui;

import android.app.Dialog;
import android.content.Context;
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
import android.widget.Button;
import android.widget.ImageView;
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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ChartFragment extends Fragment {

    private WeeklyBarChartView barChart;
    private TextView topDateTxt, topTimeTxt, middleDateTxt;
    private ImageView btnPrev, btnNext;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;

    private final List<DayData> weeklyData = new ArrayList<>();
    private int currentIndex = 6; 

    // =========================================================================
    // ПЕРЕМЕННЫЕ ДЛЯ УМНОЙ АНИМАЦИИ
    // =========================================================================
    private boolean isDataReady = false;
    private boolean isAnimated = false;
    private long[] cachedBarMillis;
    private String[] cachedDayLabels;

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

        loadWeeklyData();
        return view;
    }

    // =========================================================================
    // СПУСКОВОЙ КРЮЧОК АНИМАЦИИ
    // =========================================================================
    @Override
    public void onResume() {
        super.onResume();
        // Запускаем отрисовку графиков ТОЛЬКО если пользователь смотрит на экран
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
        
        // ИСПОЛЬЗУЕМ ГЛОБАЛЬНЫЙ ПУЛ ПОТОКОВ (с низким приоритетом для плавной отрисовки UI)
        Utils.backgroundExecutor.execute(() -> {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || !isAdded()) return;

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

                Map<String, Long> times;

                // Используем мгновенный кэш для Сегодня и Вчера, если он доступен
                if (i == 6 && UsageMath.todayExactCache != null) {
                    times = UsageMath.todayExactCache;
                } else if (i == 5 && UsageMath.yesterdayExactCache != null) {
                    times = UsageMath.yesterdayExactCache;
                } else {
                    // Считаем вручную через ядро UsageMath для старых дней
                    Calendar startCal = (Calendar) cal.clone();
                    startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0); startCal.set(Calendar.SECOND, 0); startCal.set(Calendar.MILLISECOND, 0);
                    
                    Calendar endCal = (Calendar) cal.clone();
                    endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59); endCal.set(Calendar.SECOND, 59); endCal.set(Calendar.MILLISECOND, 999);
                    
                    times = UsageMath.getFilteredExactTimes(activity, startCal.getTimeInMillis(), endCal.getTimeInMillis());
                }
                
                long dailyTotal = UsageMath.sumMap(times);
                
                // Фильтр внутри UsageMath уже отбросил мусор. Просто сортируем результат.
                List<String> apps = new ArrayList<>(times.keySet());
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

            // =========================================================================
            // ТОТАЛЬНАЯ ПРЕДЗАГРУЗКА: БЕЗ ЛИМИТОВ + ПОДДЕРЖКА УДАЛЕННЫХ ПРИЛОЖЕНИЙ
            // =========================================================================
            PackageManager pm = activity.getPackageManager();
            for (DayData day : weeklyData) {
                for (String pkgName : day.appList) {
                    try {
                        int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                                   PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                        
                        android.content.pm.ApplicationInfo info;
                        try {
                            // Ищем живое приложение
                            info = pm.getApplicationInfo(pkgName, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            // Вытаскиваем удаленного призрака!
                            info = pm.getApplicationInfo(pkgName, flag);
                        }
                        
                        pm.getApplicationLabel(info); 
                        pm.getApplicationIcon(info); 
                    } catch (Exception ignored) { }
                }
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                
                // Сохраняем данные в память, но график пока не строим
                cachedBarMillis = barMillis;
                cachedDayLabels = dayLabels;
                isDataReady = true;

                // Если вкладка активна прямо сейчас - стартуем!
                if (isResumed() && !isAnimated) {
                    runChartAnimation();
                }
            });
        });
    }

    // =========================================================================
    // ЛОГИКА ОТРИСОВКИ ГРАФИКОВ
    // =========================================================================
    private void runChartAnimation() {
        isAnimated = true; // Блокируем повторную анимацию при свайпе туда-сюда
        barChart.setData(cachedBarMillis, cachedDayLabels);
        selectDay(6); // Выбираем сегодняшний день, что заполняет тексты и список приложений
    }
}
