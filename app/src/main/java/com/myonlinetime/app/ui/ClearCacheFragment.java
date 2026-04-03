package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

import java.io.File;
import java.util.Locale;

public class ClearCacheFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_clear_cache, container, false);
        MainActivity activity = (MainActivity) getActivity();

        setupHeader(activity);

        view.findViewById(R.id.btn_action_clear_bg).setOnClickListener(v -> {
            if (activity != null) clearCustomBackground(activity);
        });

        view.findViewById(R.id.btn_action_clear_cache).setOnClickListener(v -> {
            if (activity != null) clearAppCache(activity);
        });

        return view;
    }

    private void setupHeader(MainActivity activity) {
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.header_settings_sub)); 
            
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow);

            // Оставляем колокольчик видимым, как на экране уведомлений!
            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) {
                bellBtn.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Возвращаем шапку (надпись My Online Time) при выходе
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.resetHeader();
        }
    }

    // ========================================================
    // ИСПРАВЛЕННЫЙ МЕТОД onResume
    // ========================================================
    @Override
    public void onResume() {
        super.onResume();
        // ДОБАВЛЕНО: Проверка !isHidden()
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }
    // ========================================================

    // ==========================================
    // ЛОГИКА ОЧИСТКИ ФОНА
    // ==========================================
    private void clearCustomBackground(MainActivity activity) {
        SharedPreferences profilePrefs = activity.getSharedPreferences("UserProfile", Context.MODE_PRIVATE);
        String bgPath = profilePrefs.getString("custom_bg_path", null);
        
        if (bgPath != null) {
            File bgFile = new File(bgPath);
            if (bgFile.exists()) {
                bgFile.delete(); // Физически удаляем файл
            }
            
            profilePrefs.edit()
                .remove("custom_bg_path")
                .remove("custom_bg_is_video")
                .apply();
                
            Toast.makeText(activity, getString(R.string.toast_bg_cleared), Toast.LENGTH_SHORT).show();
            
            // Фон удален, даем команду MainActivity обновить стейт
            activity.updateGlobalBackground(false); 
        } else {
            Toast.makeText(activity, getString(R.string.toast_bg_already_clear), Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // ЛОГИКА ОЧИСТКИ КЭША
    // ==========================================
    private void clearAppCache(MainActivity activity) {
        try {
            long sizeBefore = getDirSize(activity.getCacheDir());
            deleteDir(activity.getCacheDir());
            if (activity.getExternalCacheDir() != null) {
                sizeBefore += getDirSize(activity.getExternalCacheDir());
                deleteDir(activity.getExternalCacheDir());
            }

            double sizeInMb = (double) sizeBefore / (1024 * 1024);
            String formattedSize = String.format(Locale.getDefault(), "%.2f", sizeInMb);
            
            Toast.makeText(activity, getString(R.string.toast_cache_cleared, formattedSize), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(activity, getString(R.string.err_cache_clear), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) return false;
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
