package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class StatsHostFragment extends Fragment {

    public StatsHostFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_stats_host, container, false);
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return view;

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.resetHeader();
        // activity.headerTitle.setText("Статистика"); // Если нужна общая шапка

        final TextView txtTime = view.findViewById(R.id.txt_tab_time);
        final TextView txtChart = view.findViewById(R.id.txt_tab_chart);
        final TextView txtAllTime = view.findViewById(R.id.txt_tab_all_time);
        
        final View lineTime = view.findViewById(R.id.line_tab_time);
        final View lineChart = view.findViewById(R.id.line_tab_chart);
        final View lineAllTime = view.findViewById(R.id.line_tab_all_time);

        final ViewPager2 viewPager = view.findViewById(R.id.stats_view_pager);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 0: return new StatsTimeFragment(); // Наш экран со спиннером
                    case 1: return new ChartFragment(); // Твой график
                    case 2: return new AllTimeFragment(); // Твоё общее время
                    default: return new StatsTimeFragment();
                }
            }
            @Override public int getItemCount() { return 3; }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                int activeColor = ContextCompat.getColor(activity, R.color.burgundyRed);
                int inactiveColor = ContextCompat.getColor(activity, R.color.textGrayDynamic);

                txtTime.setTextColor(position == 0 ? activeColor : inactiveColor);
                txtChart.setTextColor(position == 1 ? activeColor : inactiveColor);
                txtAllTime.setTextColor(position == 2 ? activeColor : inactiveColor);

                lineTime.setBackgroundColor(position == 0 ? activeColor : 0x00000000); // 0x00000000 = transparent
                lineChart.setBackgroundColor(position == 1 ? activeColor : 0x00000000);
                lineAllTime.setBackgroundColor(position == 2 ? activeColor : 0x00000000);
            }
        });

        view.findViewById(R.id.tab_time).setOnClickListener(v -> viewPager.setCurrentItem(0, true));
        view.findViewById(R.id.tab_chart).setOnClickListener(v -> viewPager.setCurrentItem(1, true));
        view.findViewById(R.id.tab_all_time).setOnClickListener(v -> viewPager.setCurrentItem(2, true));

        return view;
    } // <-- Конец onCreateView

    // ИСПРАВЛЕНИЕ: Методы onResume и onHiddenChanged удалены!
    // Фрагмент больше не выключает видео-фон жестко и мгновенно.
}
