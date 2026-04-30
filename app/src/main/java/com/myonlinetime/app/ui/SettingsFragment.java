package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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

import java.io.File;

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

        // Инициализация текстов
        regDateTxt = view.findViewById(R.id.settings_reg_date_txt);
        accountIdTxt = view.findViewById(R.id.settings_account_id_txt);
        
        // Загружаем данные пользователя (и управляем видимостью гостевого режима)
        loadUserData(view);

        // Кнопки Аккаунта
        view.findViewById(R.id.btn_change_email).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> { /* Пока пусто */ });
        
        // Смена аккаунта
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
        
        // Умный вход / выход из аккаунта
        view.findViewById(R.id.btn_sign_out).setOnClickListener(v -> {
            if (activity != null && activity.mGoogleSignInClient != null) {
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
                
                if (account != null) {
                    // Авторизован -> Выходим и сбрасываем визуал
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.vpsToken = null; 
                        loadUserData(view); // Мгновенно перерисовываем экран (сдвигаем вверх)
                        Toast.makeText(getContext(), getString(R.string.toast_signed_out), Toast.LENGTH_SHORT).show();
                    });
                } else {
                    // НЕ авторизован -> Открываем окно входа
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    activity.startActivityForResult(signInIntent, 9001); 
                }
            }
        });

        // Оформление
        themeAuto = view.findViewById(R.id.theme_auto);
        themeLight = view.findViewById(R.id.theme_light);
        themeDark = view.findViewById(R.id.theme_dark);

        setupThemeButtons();

        // Общие настройки
        view.findViewById(R.id.btn_notifications).setOnClickListener(v -> {
            if (activity != null && activity.navigator != null) {
                activity.navigator.openSubScreen(new NotificationsFragment());
            }
        });
        
        view.findViewById(R.id.btn_saved).setOnClickListener(v -> { /* Пока пусто */ });
        
        // --- ОТКРЫВАЕМ НОВЫЙ ЭКРАН ОЧИСТКИ ---
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

        ImageView avatarView = view.findViewById(R.id.settings_avatar);
        TextView nicknameView = view.findViewById(R.id.settings_nickname);
        View btnChangeEmail = view.findViewById(R.id.btn_change_email);
        View btnDeleteAccount = view.findViewById(R.id.btn_delete_account);
        View btnSwitchAccount = view.findViewById(R.id.btn_switch_account);
        View signOutBtn = view.findViewById(R.id.btn_sign_out);

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        
        if (account != null) {
            // === АВТОРИЗОВАН: ПОКАЗЫВАЕМ БЛОК АККАУНТА ===
            if (nicknameView != null) {
                nicknameView.setVisibility(View.VISIBLE);
                // ЧИТАЕМ ИЗ ЕДИНОГО ИСТОЧНИКА ИСТИНЫ (КЭША)
                String savedName = activity.prefs.getString("my_nickname", account.getDisplayName());
                nicknameView.setText(savedName != null ? savedName : getString(R.string.default_user_name));
            }
            if (accountIdTxt != null) {
                accountIdTxt.setVisibility(View.VISIBLE);
                accountIdTxt.setText(getString(R.string.account_id_label, account.getId()));
            }
            if (regDateTxt != null) {
                regDateTxt.setVisibility(View.VISIBLE);
                // Достаем дату из кэша (если есть)
                String createdAt = activity.prefs.getString("my_created_at", "");
                if (!createdAt.isEmpty()) {
                    regDateTxt.setText(getString(R.string.settings_reg_date, createdAt));
                } else {
                    regDateTxt.setText(getString(R.string.settings_reg_date, "..."));
                }
            }
            
            if (avatarView != null) {
                avatarView.setVisibility(View.VISIBLE);
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
                } else {
                    // Запасной вариант - фото из Google
                    if (account.getPhotoUrl() != null) {
                        Glide.with(this).load(account.getPhotoUrl()).circleCrop().into(avatarView);
                    } else {
                        avatarView.setImageResource(android.R.drawable.sym_def_app_icon);
                    }
                }
            }

            // Показываем кнопки управления аккаунтом
            if (btnChangeEmail != null) btnChangeEmail.setVisibility(View.VISIBLE);
            if (btnDeleteAccount != null) btnDeleteAccount.setVisibility(View.VISIBLE);
            if (btnSwitchAccount != null) btnSwitchAccount.setVisibility(View.VISIBLE);
            
            setDynamicButtonText(signOutBtn, R.string.btn_sign_out); // Текст "Выйти"

        } else {
            // === ГОСТЬ: ПОЛНОСТЬЮ СКРЫВАЕМ БЛОК АККАУНТА ===
            if (nicknameView != null) nicknameView.setVisibility(View.GONE);
            if (accountIdTxt != null) accountIdTxt.setVisibility(View.GONE);
            if (regDateTxt != null) regDateTxt.setVisibility(View.GONE);
            if (avatarView != null) avatarView.setVisibility(View.GONE);
            
            // Скрываем лишние кнопки (интерфейс сдвинется вверх)
            if (btnChangeEmail != null) btnChangeEmail.setVisibility(View.GONE);
            if (btnDeleteAccount != null) btnDeleteAccount.setVisibility(View.GONE);
            if (btnSwitchAccount != null) btnSwitchAccount.setVisibility(View.GONE);
            
            setDynamicButtonText(signOutBtn, R.string.btn_sign_in_google); // Текст "Войти"
        }
    }

    // Универсальный метод для смены текста на кнопке входа/выхода (независимо от того, как она сделана в XML)
    private void setDynamicButtonText(View buttonView, int stringResId) {
        if (buttonView == null) return;
        if (buttonView instanceof android.widget.Button) {
            ((android.widget.Button) buttonView).setText(stringResId);
        } else if (buttonView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) buttonView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (vg.getChildAt(i) instanceof TextView) {
                    ((TextView) vg.getChildAt(i)).setText(stringResId);
                    break;
                }
            }
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

        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            themeAuto.setBackgroundResource(R.drawable.bg_button_active);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            themeLight.setBackgroundResource(R.drawable.bg_button_active);
        } else {
            themeDark.setBackgroundResource(R.drawable.bg_button_active);
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
