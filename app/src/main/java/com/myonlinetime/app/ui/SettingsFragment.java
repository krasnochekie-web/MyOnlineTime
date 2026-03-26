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
import java.util.Locale;

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
        
        if (regDateTxt != null) {
            regDateTxt.setText(getString(R.string.settings_reg_date, ""));
        }
        
        // Загружаем данные пользователя
        loadUserData(view);

        // Кнопки Аккаунта
        view.findViewById(R.id.btn_change_email).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> { /* Пока пусто */ });
        
        // Смена аккаунта
        view.findViewById(R.id.btn_switch_account).setOnClickListener(v -> {
            if (activity != null && activity.mGoogleSignInClient != null) {
                activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                    activity.vpsToken = null;
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    activity.startActivityForResult(signInIntent, 9001); 
                });
            }
        });
        
        // Умный выход из аккаунта
        view.findViewById(R.id.btn_sign_out).setOnClickListener(v -> {
            if (activity != null && activity.mGoogleSignInClient != null) {
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
                
                if (account != null) {
                    // Авторизован -> Выходим и сбрасываем визуал
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.vpsToken = null; 
                        loadUserData(view); 
                        Toast.makeText(getContext(), "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show();
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
        view.findViewById(R.id.btn_notifications).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_saved).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_clear_cache).setOnClickListener(v -> clearAppCache());

        // Прочее
        View.OnClickListener openSiteListener = v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://map.krasnocraft.ru"));
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

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        
        if (account != null) {
            if (nicknameView != null) {
                nicknameView.setText(account.getDisplayName() != null ? account.getDisplayName() : "Пользователь");
            }
            if (accountIdTxt != null) {
                accountIdTxt.setText("ID аккаунта: " + account.getId());
                accountIdTxt.setVisibility(View.VISIBLE);
            }
            if (avatarView != null) {
                Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + account.getId());
                if (cachedAvatar != null) {
                    Glide.with(this).load(cachedAvatar).circleCrop().into(avatarView);
                } else {
                    File file = new File(activity.getFilesDir(), "avatar_" + account.getId() + ".png");
                    if (file.exists()) {
                        Glide.with(this).load(file).circleCrop().into(avatarView);
                    } else if (account.getPhotoUrl() != null) {
                        Glide.with(this).load(account.getPhotoUrl()).circleCrop().into(avatarView);
                    }
                }
            }
        } else {
            // Гость: Сбрасываем данные
            if (nicknameView != null) nicknameView.setText("Guest");
            if (accountIdTxt != null) {
                accountIdTxt.setText(""); 
                accountIdTxt.setVisibility(View.GONE); 
            }
            if (avatarView != null) {
                avatarView.setImageResource(R.drawable.ic_profile_placeholder); 
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
        // Ставим всем неактивный динамический фон
        themeAuto.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeLight.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeDark.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);

        // Ставим бордовый фон только выбранной теме
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            themeAuto.setBackgroundResource(R.drawable.bg_button_active);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            themeLight.setBackgroundResource(R.drawable.bg_button_active);
        } else {
            themeDark.setBackgroundResource(R.drawable.bg_button_active);
        }
    }

    private void clearAppCache() {
        try {
            Context context = requireContext();
            long sizeBefore = getDirSize(context.getCacheDir());
            
            deleteDir(context.getCacheDir());
            if (context.getExternalCacheDir() != null) {
                sizeBefore += getDirSize(context.getExternalCacheDir());
                deleteDir(context.getExternalCacheDir());
            }

            double sizeInMb = (double) sizeBefore / (1024 * 1024);
            String formattedSize = String.format(Locale.getDefault(), "%.2f", sizeInMb);
            
            Toast.makeText(context, getString(R.string.toast_cache_cleared, formattedSize), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка при очистке кэша", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        size += file.length();
                    } else if (file.isDirectory()) {
                        size += getDirSize(file);
                    }
                }
            }
        } else if (dir != null && dir.isFile()) {
            size = dir.length();
        }
        return size;
    }
}
