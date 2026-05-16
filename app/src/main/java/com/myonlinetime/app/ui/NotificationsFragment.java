package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class NotificationsFragment extends Fragment {

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_GENERAL = "notif_general_enabled";
    private static final String KEY_RECORDS = "notif_records_enabled";
    private static final String KEY_FOLLOWERS = "notif_followers_enabled"; 

    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications, container, false);

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        SwitchCompat switchGeneral = view.findViewById(R.id.switch_general);
        SwitchCompat switchRecords = view.findViewById(R.id.switch_records);
        SwitchCompat switchFollowers = view.findViewById(R.id.switch_followers); 
        
        View containerRecords = view.findViewById(R.id.container_records);
        View containerFollowers = view.findViewById(R.id.container_followers); 

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.clearPreviewBackground();
            activity.updateGlobalBackground(false);
        }

        // ПРОВЕРКА НА ГОСТЯ
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(requireContext());
        boolean isGuest = (acct == null || (activity != null && activity.vpsToken == null));

        // Восстанавливаем сохраненные значения (по умолчанию всё включено)
        boolean isGeneralEnabled = prefs.getBoolean(KEY_GENERAL, true);
        boolean isRecordsEnabled = prefs.getBoolean(KEY_RECORDS, true);
        boolean isFollowersEnabled = prefs.getBoolean(KEY_FOLLOWERS, true); 

        switchGeneral.setChecked(isGeneralEnabled);
        switchRecords.setChecked(isRecordsEnabled);
        
        // Визуальное состояние зависимых элементов для Рекордов
        containerRecords.setAlpha(isGeneralEnabled ? 1.0f : 0.5f);
        switchRecords.setEnabled(isGeneralEnabled);

        // ЛОГИКА ДЛЯ ПОДПИСОК (Блокировка для гостей)
        if (isGuest) {
            switchFollowers.setChecked(false);
            switchFollowers.setEnabled(false);
            containerFollowers.setAlpha(0.5f);
        } else {
            switchFollowers.setChecked(isFollowersEnabled);
            switchFollowers.setEnabled(isGeneralEnabled);
            containerFollowers.setAlpha(isGeneralEnabled ? 1.0f : 0.5f);
        }

        // Слушатель для Общих уведомлений
        switchGeneral.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_GENERAL, isChecked).apply();
            
            containerRecords.setAlpha(isChecked ? 1.0f : 0.5f);
            switchRecords.setEnabled(isChecked);
            
            // Динамическая проверка на гостя при переключении главного рубильника
            GoogleSignInAccount currentAcct = GoogleSignIn.getLastSignedInAccount(requireContext());
            boolean currentlyGuest = (currentAcct == null || (activity != null && activity.vpsToken == null));

            if (currentlyGuest) {
                containerFollowers.setAlpha(0.5f);
                switchFollowers.setEnabled(false);
                switchFollowers.setChecked(false);
            } else {
                containerFollowers.setAlpha(isChecked ? 1.0f : 0.5f);
                switchFollowers.setEnabled(isChecked);
            }
        });

        // Слушатель для Рекордов
        switchRecords.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_RECORDS, isChecked).apply();
        });

        // Слушатель для Подписок
        switchFollowers.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_FOLLOWERS, isChecked).apply();
        });

        setupHeader();

        return view;
    }

    private void setupHeader() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.header_settings_sub));
            
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 
            
            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) {
                bellBtn.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !activity.navigator.hasSubScreen()) {
            activity.headerManager.resetHeader();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            setupHeader();
            activity.updateGlobalBackground(false); 
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }
}
