package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

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
            // Прячем общую шапку (если нужно, чтобы аватарка была самой верхней)
            // activity.mainHeader.setVisibility(View.GONE); 
        }

        // Инициализация текстов
        regDateTxt = view.findViewById(R.id.settings_reg_date_txt);
        accountIdTxt = view.findViewById(R.id.settings_account_id_txt);
        
        // Пока оставляем пустыми, как договаривались
        regDateTxt.setText(getString(R.string.settings_reg_date, ""));
        accountIdTxt.setText(getString(R.string.settings_account_id, ""));

        // Кнопки Аккаунта
        view.findViewById(R.id.btn_change_email).setOnClickListener(v -> { /* Пока пусто */ });
        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> { /* Пока пусто */ });
        
        view.findViewById(R.id.btn_switch_account).setOnClickListener(v -> {
            // TODO: Вызов Google Sign In Client для выбора аккаунта
            Toast.makeText(getContext(), "Выбор аккаунта Google", Toast.LENGTH_SHORT).show();
        });
        
        view.findViewById(R.id.btn_sign_out).setOnClickListener(v -> {
            // TODO: Вызов Google Sign Out
            Toast.makeText(getContext(), "Выход из аккаунта", Toast.LENGTH_SHORT).show();
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

    private void setupThemeButtons() {
        // Читаем сохраненную тему (по умолчанию Темная, как у тебя)
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
        // Сбрасываем цвета кнопок
        themeAuto.setBackgroundResource(R.drawable.bg_button_dark);
        themeLight.setBackgroundResource(R.drawable.bg_button_dark);
        themeDark.setBackgroundResource(R.drawable.bg_button_dark);

        // Подсвечиваем активную (укажи свой drawable для выделенной кнопки, например bg_button_active)
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
            
            // Удаляем внутренний кэш
            deleteDir(context.getCacheDir());
            // Если нужно, удаляем и внешний кэш (картинки, файлы)
            if (context.getExternalCacheDir() != null) {
                sizeBefore += getDirSize(context.getExternalCacheDir());
                deleteDir(context.getExternalCacheDir());
            }

            // Переводим байты в мегабайты (формат с 2 знаками после запятой)
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
          
