package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class NotificationsHistoryFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        RecyclerView recycler = view.findViewById(R.id.recycler_notifications);
        TextView emptyText = view.findViewById(R.id.empty_notif_text);

        // Позже здесь будет подключен адаптер. Пока показываем надпись "Уведомлений нет"
        recycler.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);

        return view;
    }

    private void setupHeader() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.title_notifications));
            
            // Показываем стрелку назад
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 

            // СКРЫВАЕМ КОЛОКОЛЬЧИК! Зачем заходить в уведомления из уведомлений? 😎
            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) {
                bellBtn.setVisibility(View.GONE);
            }
            
            // Клик по стрелке закрывает этот фрагмент
            activity.headerBackBtn.setOnClickListener(v -> {
                activity.getSupportFragmentManager().popBackStack();
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Возвращаем шапку (и колокольчик) в исходное состояние при выходе
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.resetHeader();
            
            // Возвращаем колокольчик
            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) {
                bellBtn.setVisibility(View.VISIBLE);
            }
        }
    }
}
