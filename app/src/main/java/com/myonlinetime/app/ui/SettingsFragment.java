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
        
        // Пока оставляем дату пустой, как договаривались
        regDateTxt.setText(getString(R.string.settings_reg_date, ""));
        
        // ЗАГРУЖАЕМ ДАННЫЕ ПРОФИЛЯ (Аватарка, Никнейм, ID)
        loadUserData(view);

        // Кнопки Аккаунта
        view.findViewById(R.id.btn_change_email).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> { /* Пока пусто */ });
        
        // --- ЛОГИКА СМЕНЫ АККАУНТА ---
        view.findViewById(R.id.btn_switch_account).setOnClickListener(v -> {
            if (activity != null && activity.mGoogleSignInClient != null) {
                // Сначала выходим, чтобы Google показал окно выбора аккаунта
                activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                    activity.vpsToken = null;
                    // Вызываем интент входа через MainActivity
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    activity.startActivityForResult(signInIntent, 9001); 
                });
            }
        });
// --- ЛОГИКА ВЫХОДА ИЗ АККАУНТА ---
        view.findViewById(R.id.btn_sign_out).setOnClickListener(v -> {
            if (activity != null && activity.mGoogleSignInClient != null) {
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
                
                if (account != null) {
                    // 1. Пользователь авторизован -> Выходим
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.vpsToken = null; // Очищаем токен
                        loadUserData(view); // Сбрасываем визуал до Guest
                        Toast.makeText(getContext(), "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    // 2. Пользователь НЕ авторизован -> Открываем окно выбора Google-аккаунта
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    activity.startActivityForResult(signInIntent, 9001); 
                }
            }
        });
        // Кнопки Оформления
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

// --- Загрузка аватарки и данных пользователя ---
    private void loadUserData(View view) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        ImageView avatarView = view.findViewById(R.id.settings_avatar);
        TextView nicknameView = view.findViewById(R.id.settings_nickname);

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        
        if (account != null) {
            // ЕСЛИ АВТОРИЗОВАН: Устанавливаем имя и ID
            if (nicknameView != null) {
                nicknameView.setText(account.getDisplayName() != null ? account.getDisplayName() : "Пользователь");
            }
            if (accountIdTxt != null) {
                accountIdTxt.setText("ID аккаунта: " + account.getId());
                accountIdTxt.setVisibility(View.VISIBLE);
            }

            // Загружаем аватарку
            if (avatarView != null) {
                android.graphics.Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + account.getId());
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
            // ЕСЛИ НЕ АВТОРИЗОВАН (Гость): Сбрасываем данные
            if (nicknameView != null) {
                nicknameView.setText("Guest");
            }
            if (accountIdTxt != null) {
                accountIdTxt.setText(""); 
                accountIdTxt.setVisibility(View.GONE); // Скрываем строку ID, чтобы не висела пустой
            }
            if (avatarView != null) {
                // Возвращаем стандартную картинку
                avatarView.setImageResource(R.drawable.ic_profile_placeholder); 
            }
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
        themeAuto.setBackgroundResource(R.drawable.bg_button_dark);
        themeLight.setBackgroundResource(R.drawable.bg_button_dark);
        themeDark.setBackgroundResource(R.drawable.bg_button_dark);

        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            themeAuto.setBackgroundResource(R.drawable.bg_button_active);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            themeLight.setBackgroundResource(R.drawable.bg_button_active);
        } else {
            themeDark.setBackgroundResource(R.drawable.bg_button_active);
        }
    }

    // --- ЛОГИКА ОЧИСТКИ КЭША ---
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
        } else {
            return false;
        }
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
