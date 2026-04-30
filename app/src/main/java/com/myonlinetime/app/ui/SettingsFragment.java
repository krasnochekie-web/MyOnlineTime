package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

public class SettingsFragment extends Fragment {

    private TextView themeAuto, themeLight, themeDark;
    private TextView regDateTxt, accountIdTxt;
    private SharedPreferences prefs;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_THEME = "selected_theme";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_settings, container, false);
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        regDateTxt = view.findViewById(R.id.settings_reg_date_txt);
        accountIdTxt = view.findViewById(R.id.settings_account_id_txt);
        
        loadUserData(view);

        // Кнопки Аккаунта
        view.findViewById(R.id.btn_change_email).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> { /* Пока пусто */ });
        
        View btnSwitch = view.findViewById(R.id.btn_switch_account);
        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> {
                if (activity != null && activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.vpsToken = null;
                        Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                        activity.startActivityForResult(signInIntent, 9001); 
                    });
                }
            });
        }
        
        View btnSignOut = view.findViewById(R.id.btn_sign_out);
        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                if (activity != null && activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.vpsToken = null; 
                        loadUserData(view); // Скрываем верхний блок, настройки улетают вверх
                        Toast.makeText(getContext(), getString(R.string.settings_sign_out), Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }

        // Оформление
        themeAuto = view.findViewById(R.id.theme_auto);
        themeLight = view.findViewById(R.id.theme_light);
        themeDark = view.findViewById(R.id.theme_dark);
        setupThemeButtons();

        // Общие
        view.findViewById(R.id.btn_notifications).setOnClickListener(v -> {
            if (activity != null && activity.navigator != null) {
                activity.navigator.openSubScreen(new NotificationsHistoryFragment());
            }
        });
        view.findViewById(R.id.btn_clear_cache).setOnClickListener(v -> {
            if (activity != null && activity.navigator != null) {
                activity.navigator.openSubScreen(new ClearCacheFragment());
            }
        });

        // Прочее
        View.OnClickListener openSiteListener = v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_map)));
            startActivity(browserIntent);
        };
        view.findViewById(R.id.btn_share_app).setOnClickListener(openSiteListener);
        view.findViewById(R.id.btn_write_review).setOnClickListener(openSiteListener);

        return view;
    }

    private void loadUserData(View view) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        // Находим ВЕСЬ контейнер данных пользователя и блок аккаунта
        View userHeaderBlock = view.findViewById(R.id.settings_user_header_block);
        View accountBlock = view.findViewById(R.id.settings_account_block);

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        
        if (account != null) {
            // АВТОРИЗОВАН: Показываем блоки, заполняем данными
            if (userHeaderBlock != null) userHeaderBlock.setVisibility(View.VISIBLE);
            if (accountBlock != null) accountBlock.setVisibility(View.VISIBLE);

            ImageView avatarView = view.findViewById(R.id.settings_avatar);
            TextView nicknameView = view.findViewById(R.id.settings_nickname);

            if (nicknameView != null) {
                String savedName = activity.prefs.getString("my_nickname", account.getDisplayName());
                nicknameView.setText(savedName);
            }
            if (accountIdTxt != null) {
                accountIdTxt.setText(getString(R.string.account_id_label, account.getId()));
            }
            if (regDateTxt != null) {
                String createdAt = activity.prefs.getString("my_created_at", "");
                regDateTxt.setText(getString(R.string.settings_reg_date, createdAt.isEmpty() ? "..." : createdAt));
            }
            if (avatarView != null) {
                String savedAvatar = activity.prefs.getString("my_photo_base64", null);
                if (savedAvatar != null) {
                    if (savedAvatar.startsWith("http")) {
                        Glide.with(this).load(savedAvatar).circleCrop().into(avatarView);
                    } else {
                        try {
                            byte[] bytes = android.util.Base64.decode(savedAvatar, android.util.Base64.DEFAULT);
                            Glide.with(this).load(bytes).circleCrop().into(avatarView);
                        } catch (Exception e){}
                    }
                } else if (account.getPhotoUrl() != null) {
                    Glide.with(this).load(account.getPhotoUrl()).circleCrop().into(avatarView);
                }
            }
        } else {
            // ВЫШЕЛ: Просто скрываем все блоки аккаунта. Контент снизу подтянется вверх.
            if (userHeaderBlock != null) userHeaderBlock.setVisibility(View.GONE);
            if (accountBlock != null) accountBlock.setVisibility(View.GONE);
        }
    }

    private void setupThemeButtons() {
        int currentTheme = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        updateThemeUI(currentTheme);
        themeAuto.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        themeLight.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_NO));
        themeDark.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_YES));
    }

    private void setTheme(int mode) {
        prefs.edit().putInt(KEY_THEME, mode).apply();
        updateThemeUI(mode);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void updateThemeUI(int mode) {
        themeAuto.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeLight.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeDark.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) themeAuto.setBackgroundResource(R.drawable.bg_button_active);
        else if (mode == AppCompatDelegate.MODE_NIGHT_NO) themeLight.setBackgroundResource(R.drawable.bg_button_active);
        else themeDark.setBackgroundResource(R.drawable.bg_button_active);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }
}
