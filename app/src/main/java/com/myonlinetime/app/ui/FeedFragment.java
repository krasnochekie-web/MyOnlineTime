package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class FeedFragment extends Fragment {

    public FeedFragment() {
        // Обязательный пустой конструктор
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_feed, container, false);

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            // Обязательно восстанавливаем дефолтную шапку при открытии ленты
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        return view;
    } // <-- Здесь заканчивается onCreateView

    // ========================================================
    // ВОТ ОН - МЕТОД onResume ДЛЯ ВЫКЛЮЧЕНИЯ ФОНА!
    // ========================================================
    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); // Выключает видео-фон
        }
    }
    // ========================================================

} // <-- А здесь заканчивается сам класс FeedFragment
