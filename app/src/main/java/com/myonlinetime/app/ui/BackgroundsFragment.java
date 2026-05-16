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

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class BackgroundsFragment extends Fragment {

    private static final String PREFS_NAME = "AppPrefs";
    
    // Ключи жестко заданы
    public static final String KEY_BG_GLOBAL = "bg_global_enabled";
    public static final String KEY_BG_MY_PROFILE = "bg_my_profile_enabled";
    public static final String KEY_BG_MY_IMAGES = "bg_my_images_enabled";
    public static final String KEY_BG_MY_GIFS = "bg_my_gifs_enabled";
    public static final String KEY_BG_OTHERS_PROFILE = "bg_others_profile_enabled";
    public static final String KEY_BG_OTHERS_IMAGES = "bg_others_images_enabled";
    public static final String KEY_BG_OTHERS_GIFS = "bg_others_gifs_enabled";

    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_backgrounds, container, false);

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        SwitchCompat switchGlobal = view.findViewById(R.id.switch_global_bg);
        
        SwitchCompat switchMyProfile = view.findViewById(R.id.switch_my_profile_bg);
        SwitchCompat switchMyImages = view.findViewById(R.id.switch_my_images);
        SwitchCompat switchMyGifs = view.findViewById(R.id.switch_my_gifs);
        
        SwitchCompat switchOthersProfile = view.findViewById(R.id.switch_others_profile_bg);
        SwitchCompat switchOthersImages = view.findViewById(R.id.switch_others_images);
        SwitchCompat switchOthersGifs = view.findViewById(R.id.switch_others_gifs);

        View containerMyProfile = view.findViewById(R.id.container_my_profile);
        View containerMySubItems = view.findViewById(R.id.container_my_sub_items);
        View containerOthersProfile = view.findViewById(R.id.container_others_profile);
        View containerOthersSubItems = view.findViewById(R.id.container_others_sub_items);

        boolean isGlobalOn = prefs.getBoolean(KEY_BG_GLOBAL, true);
        boolean isMyProfileOn = prefs.getBoolean(KEY_BG_MY_PROFILE, true);
        boolean isMyImagesOn = prefs.getBoolean(KEY_BG_MY_IMAGES, true);
        boolean isMyGifsOn = prefs.getBoolean(KEY_BG_MY_GIFS, true);
        boolean isOthersProfileOn = prefs.getBoolean(KEY_BG_OTHERS_PROFILE, true);
        boolean isOthersImagesOn = prefs.getBoolean(KEY_BG_OTHERS_IMAGES, true);
        boolean isOthersGifsOn = prefs.getBoolean(KEY_BG_OTHERS_GIFS, true);

        switchGlobal.setChecked(isGlobalOn);
        switchMyProfile.setChecked(isMyProfileOn);
        switchMyImages.setChecked(isMyImagesOn);
        switchMyGifs.setChecked(isMyGifsOn);
        switchOthersProfile.setChecked(isOthersProfileOn);
        switchOthersImages.setChecked(isOthersImagesOn);
        switchOthersGifs.setChecked(isOthersGifsOn);

        Runnable updateUIState = () -> {
            boolean global = switchGlobal.isChecked();
            boolean myProfile = switchMyProfile.isChecked();
            boolean othersProfile = switchOthersProfile.isChecked();

            containerMyProfile.setAlpha(global ? 1.0f : 0.5f);
            containerOthersProfile.setAlpha(global ? 1.0f : 0.5f);
            
            switchMyProfile.setEnabled(global);
            switchOthersProfile.setEnabled(global);

            boolean mySubEnabled = global && myProfile;
            containerMySubItems.setAlpha(mySubEnabled ? 1.0f : 0.5f);
            switchMyImages.setEnabled(mySubEnabled);
            switchMyGifs.setEnabled(mySubEnabled);

            boolean othersSubEnabled = global && othersProfile;
            containerOthersSubItems.setAlpha(othersSubEnabled ? 1.0f : 0.5f);
            switchOthersImages.setEnabled(othersSubEnabled);
            switchOthersGifs.setEnabled(othersSubEnabled);
        };

        updateUIState.run();

        // ИСПРАВЛЕНО: передаем TRUE, чтобы MainActivity ПЕРЕРИСОВАЛ фон, а не просто скрыл
        Runnable notifyBgChanged = () -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).updateGlobalBackground(true);
            }
        };

        switchGlobal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_GLOBAL, isChecked).apply();
            updateUIState.run();
            notifyBgChanged.run();
        });

        switchMyProfile.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_MY_PROFILE, isChecked).apply();
            updateUIState.run();
            notifyBgChanged.run();
        });

        switchMyImages.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_MY_IMAGES, isChecked).apply();
            notifyBgChanged.run();
        });

        switchMyGifs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_MY_GIFS, isChecked).apply();
            notifyBgChanged.run();
        });

        switchOthersProfile.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_OTHERS_PROFILE, isChecked).apply();
            updateUIState.run();
            notifyBgChanged.run();
        });

        switchOthersImages.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_OTHERS_IMAGES, isChecked).apply();
            notifyBgChanged.run();
        });

        switchOthersGifs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BG_OTHERS_GIFS, isChecked).apply();
            notifyBgChanged.run();
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
            activity.updateGlobalBackground(true); // Тоже заставляем перерисовать при возврате
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(true); 
        }
    }
                                                        }
            
