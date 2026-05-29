package com.myonlinetime.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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

public class ChartFragment extends Fragment {

    private WeeklyBarChartView barChart;
    private TextView topDateTxt, topTimeTxt, middleDateTxt;
    private ImageView btnPrev, btnNext;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;

    private FrameLayout loadingOverlay;
    private Dialog howItWorksDialog;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final List<DayData> weeklyData = new ArrayList<>();
    private int currentIndex = 6;

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
            howItWorksBtn.setOnClickListener(v -> uiHandler.postDelayed(() -> showHowItWorksDialog(false), 150));
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new AppsAdapter(activity, R.layout.item_app_usage_time, false);
        recyclerView.setAdapter(adapter);

        barChart.setListener(this::selectDay);

        btnPrev.setOnClickListener(v -> { if (currentIndex > 0) selectDay(currentIndex - 1); });
        btnNext.setOnClickListener(v -> { if (currentIndex < 6) selectDay(currentIndex + 1); });

        loadingOverlay = new FrameLayout(activity);
        loadingOverlay.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadingOverlay.setClickable(true);
        loadingOverlay.setFocusable(true);
        loadingOverlay.setBackgroundColor(Color.TRANSPARENT);

        ProgressBar spinner = new ProgressBar(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.grapefruit)));
        }
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(
                (int)(50 * getResources().getDisplayMetrics().density),
                (int)(50 * getResources().getDisplayMetrics().density)
        );
        spinnerParams.gravity = android.view.Gravity.CENTER;
        loadingOverlay.addView(spinner, spinnerParams);

        ((ViewGroup) view).addView(loadingOverlay);

        uiHandler.postDelayed(this::loadWeeklyData, 200);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isDataReady && !isAnimated) runChartAnimation();
    }

    private void showHowItWorksDialog(boolean isAllTime) {
        if (howItWorksDialog != null && howItWorksDialog.isShowing()) return;

        howItWorksDialog = new Dialog(requireContext());
        howItWorksDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        howItWorksDialog.setContentView(R.layout.dialog_how_it_works);

        if (howItWorksDialog.getWindow() != null) {
            howItWorksDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            howItWorksDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            howItWorksDialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        }

        TextView descText = howItWorksDialog.findViewById(R.id.dialog_description_text);
        Button btnOk = howItWorksDialog.findViewById(R.id.dialog_ok_btn);

        descText.setText(getString(isAllTime ? R.string.dialog_how_it_works_all_time : R.string.dialog_how_it_works_charts));
        btnOk.setOnClickListener(v -> howItWorksDialog.dismiss());
        howItWorksDialog.show();
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
        Utils.backgroundExecutor.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || !isAdded()) return;

            // Если процесс пережил полночь — актуализируем границы суток и сбрасываем
            // протухшие посуточные кэши, чтобы не нарисовать данные не того дня.
            UsageMath.refreshDayBoundariesIfNeeded();

            String[] daysArray = getResources().getStringArray(R.array.days_short);
            String[] monthsArray = getResources().getStringArray(R.array.months_custom);

            long[] barMillis = new long[7];
            String[] dayLabels = new String[7];
            weeklyData.clear();

            for (int i = 0; i < 7; i++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -(6 - i));

                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                long dayStart = cal.getTimeInMillis();

                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999);
                long dayEnd = cal.getTimeInMillis();

                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;
                String dayShort = daysArray[dayOfWeek];
                int dayNum = cal.get(Calendar.DAY_OF_MONTH);
                String monthStr = monthsArray[cal.get(Calendar.MONTH)];

                Map<String, Long> times;

                // Статический кэш используем ТОЛЬКО если его дата реально совпадает
                // с рисуемым днём — иначе (например, после полуночи) считаем заново
                // корректным однодневным окном, без подмешивания чужого дня.
                if (i == 6 && UsageMath.todayExactCache != null && dayStart == UsageMath.todayStartMillis) {
                    times = UsageMath.todayExactCache;
                } else if (i == 5 && UsageMath.yesterdayExactCache != null && dayStart == UsageMath.yesterdayStartMillis) {
                    times = UsageMath.yesterdayExactCache;
                } else {
                    times = UsageMath.getFilteredExactTimes(activity, dayStart, dayEnd);
                }

                long dailyTotal = UsageMath.sumMap(times);

                // Защита от аномалий: больше 24ч в сутках быть не может.
                // НЕ чистим возможный общий статический кэш (times.clear() мог его обнулить),
                // а просто отвязываемся в новую коллекцию.
                if (dailyTotal > 86400000L) {
                    dailyTotal = 0;
                    times = new HashMap<>();
                }

                List<String> apps = new ArrayList<>(times.keySet());
                final Map<String, Long> finalTimes = times;
                Collections.sort(apps, (left, right) -> Long.compare(finalTimes.get(right), finalTimes.get(left)));

                DayData dayData = new DayData();
                dayData.totalMillis = dailyTotal;
                dayData.dayOfWeekShort = dayShort;
                dayData.dateTopStr = getString(R.string.format_date_top, dayShort, dayNum, monthStr);
                dayData.dateMiddleStr = getString(R.string.format_date_middle, dayShort, dayNum, monthStr);
                dayData.appList = apps;
                dayData.appTimes = times;

                weeklyData.add(dayData);
                barMillis[i] = dailyTotal;
                dayLabels[i] = dayShort;
            }

            PackageManager pm = activity.getPackageManager();
            for (DayData day : weeklyData) {
                for (String pkgName : day.appList) {
                    try {
                        int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ?
                                PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                        pm.getApplicationInfo(pkgName, flag);
                    } catch (Exception ignored) { }
                }
            }

            uiHandler.post(() -> {
                if (!isAdded()) return;
                cachedBarMillis = barMillis;
                cachedDayLabels = dayLabels;
                isDataReady = true;

                if (loadingOverlay != null) {
                    loadingOverlay.setVisibility(View.GONE);
                }

                if (isResumed() && !isAnimated) runChartAnimation();
            });
        });
    }

    private void runChartAnimation() {
        isAnimated = true;
        barChart.setData(cachedBarMillis, cachedDayLabels);
        selectDay(6);
    }
}
