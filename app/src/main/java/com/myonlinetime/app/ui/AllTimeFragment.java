package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
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

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class AllTimeFragment extends Fragment {

    private TextView mainValTxt, subValTxt, descTxt, yesterdayValTxt;
    private RecyclerView recyclerView;
    private AppsAdapter adapter;
    private SharedPreferences prefs;

    private static final String PREF_NAME = "AllTimeStatsCache";
    private static final String KEY_START_DATE = "start_date_millis";

    // Переменные для анимации
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

        // Сбрасываем текст перед анимацией
        mainValTxt.setText(getString(R.string.format_days_hours, 0, 0));
        subValTxt.setText(getString(R.string.format_total_hours_mins, 0, 0));
        yesterdayValTxt.setText(getString(R.string.format_plus_hours_mins, 0, 0));

        tryLoadAllTimeData();

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

    // =========================================================================
    // ПОДКЛЮЧЕНИЕ К ГЛОБАЛЬНОМУ КЭШУ (Режим Ждуна)
    // =========================================================================
    private void tryLoadAllTimeData() {
        // Нам нужны два индекса из глобального кэша: 5 (За всё время) и 1 (Вчера)
        if (UsageMath.globalTimeCache.containsKey(5) && UsageMath.globalTimeCache.containsKey(1)) {
            checkStartDateAndPrepareUi();
        } else if (Boolean.TRUE.equals(UsageMath.isCalculating.get(5)) || Boolean.TRUE.equals(UsageMath.isCalculating.get(1))) {
            pollCache();
        } else {
            if (getActivity() != null) {
                UsageMath.preloadAbsoluteEverything(getActivity());
                pollCache();
            }
        }
    }

    private void pollCache() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) return;
            if (UsageMath.globalTimeCache.containsKey(5) && UsageMath.globalTimeCache.containsKey(1)) {
                checkStartDateAndPrepareUi();
            } else {
                pollCache();
            }
        }, 150);
    }

    // =========================================================================
    // ПОДГОТОВКА ДАННЫХ И ОТРИСОВКА
    // =========================================================================
    private void checkStartDateAndPrepareUi() {
        long startDate = prefs.getLong(KEY_START_DATE, 0);
        if (startDate > 0) {
            cachedStartDate = startDate;
            applyDataToUi();
        } else {
            // Ищем дату первого запуска только ОДИН раз за всю жизнь приложения
            Utils.backgroundExecutor.execute(() -> {
                long earliestStart = System.currentTimeMillis();
                if (getActivity() != null) {
                    UsageStatsManager usm = (UsageStatsManager) getActivity().getSystemService(Context.USAGE_STATS_SERVICE);
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.YEAR, -5); 
                    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, cal.getTimeInMillis(), System.currentTimeMillis());
                    if (stats != null) {
                        for (UsageStats stat : stats) {
                            if (stat.getFirstTimeStamp() > 0 && stat.getFirstTimeStamp() < earliestStart) {
                                earliestStart = stat.getFirstTimeStamp();
                            }
                        }
                    }
                }
                final long finalStart = earliestStart;
                prefs.edit().putLong(KEY_START_DATE, finalStart).apply();
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    cachedStartDate = finalStart;
                    applyDataToUi();
                });
            });
        }
    }

    private void applyDataToUi() {
        // Забираем готовые данные из Главного Архивариуса за 0 миллисекунд
        UsageMath.AppStatsResult allTimeData = UsageMath.globalTimeCache.get(5);
        UsageMath.AppStatsResult yesterdayData = UsageMath.globalTimeCache.get(1);

        if (allTimeData == null || yesterdayData == null) return;

        cachedTotalMillis = allTimeData.totalMillis;
        cachedYesterdayTotal = yesterdayData.totalMillis;

        adapter.updateData(allTimeData.list, allTimeData.times);
        
        isDataReady = true;
        if (isResumed() && !isAnimated) {
            runNumbersAnimation();
        }
    }

    // =========================================================================
    // ЛОГИКА АНИМАЦИИ БЕЗ ИЗМЕНЕНИЙ (Работает идеально)
    // =========================================================================
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
                                         
