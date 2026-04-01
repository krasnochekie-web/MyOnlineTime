package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class NotificationsFragment extends Fragment {

    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications, container, false);

        prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);

        SwitchCompat switchGeneral = view.findViewById(R.id.switch_general);
        SwitchCompat switchRecords = view.findViewById(R.id.switch_records);
        View containerRecords = view.findViewById(R.id.container_records);

        // Восстанавливаем сохраненные значения (по умолчанию всё включено)
        boolean isGeneralEnabled = prefs.getBoolean("notif_general_enabled", true);
        boolean isRecordsEnabled = prefs.getBoolean("notif_records_enabled", true);

        switchGeneral.setChecked(isGeneralEnabled);
        switchRecords.setChecked(isRecordsEnabled);
        
        // Если выключены общие — визуально отключаем рекорды
        containerRecords.setAlpha(isGeneralEnabled ? 1.0f : 0.5f);
        switchRecords.setEnabled(isGeneralEnabled);

        // Слушатель для Общих уведомлений
        switchGeneral.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("notif_general_enabled", isChecked).apply();
            containerRecords.setAlpha(isChecked ? 1.0f : 0.5f);
            switchRecords.setEnabled(isChecked);
        });

        // Слушатель для Рекордов
        switchRecords.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("notif_records_enabled", isChecked).apply();
        });

        setupHeader();

        return view;
    }

    private void setupHeader() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.header_settings_sub));
            
            // Показываем стрелку назад
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 
            
            // ВНИМАНИЕ: Я удалил activity.headerBackBtn.setOnClickListener(...) 
            // Теперь работает твой правильный AppNavigator из MainActivity!
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Возвращаем шапку в исходное состояние при выходе
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.resetHeader();
        }
    }
}
