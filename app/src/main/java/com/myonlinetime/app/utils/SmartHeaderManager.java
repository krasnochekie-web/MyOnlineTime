package com.myonlinetime.app.utils;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.myonlinetime.app.R;

import java.util.HashMap;
import java.util.Map;

public class SmartHeaderManager {

    private final AppCompatActivity activity;
    private final TextView headerTitle;
    private final ImageView headerBackBtn;
    private final View bellContainer;
    private final ImageView bellBtn;

    private final Map<Integer, CharSequence> backStackTitles = new HashMap<>();
    private boolean isRestoringTitle = false;
    
    // Коллбэк для вызова метода обновления бейджика в MainActivity
    private final Runnable updateBadgeCallback;

    public SmartHeaderManager(AppCompatActivity activity, Runnable updateBadgeCallback) {
        this.activity = activity;
        this.updateBadgeCallback = updateBadgeCallback;

        this.headerTitle = activity.findViewById(R.id.header_title);
        this.headerBackBtn = activity.findViewById(R.id.header_back_btn);
        this.bellContainer = activity.findViewById(R.id.header_bell_container);
        this.bellBtn = activity.findViewById(R.id.header_bell_btn);

        setupListeners();
    }

    private void setupListeners() {
        // 1. Следим за изменением текста
        headerTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!isRestoringTitle) {
                    int depth = activity.getSupportFragmentManager().getBackStackEntryCount();
                    backStackTitles.put(depth, s.toString());
                }
            }
        });

        // 2. Слушаем нативный стек навигации
        activity.getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            int depth = activity.getSupportFragmentManager().getBackStackEntryCount();
            if (depth == 0) {
                headerManager.resetHeader();
            } else {
                showSecondaryHeader(depth);
            }
        });
    }

    private void showSecondaryHeader(int depth) {
        headerBackBtn.setVisibility(View.VISIBLE);
        
        if (bellContainer != null) bellContainer.setVisibility(View.GONE);
        if (bellBtn != null) bellBtn.setVisibility(View.GONE);

        if (backStackTitles.containsKey(depth)) {
            isRestoringTitle = true;
            headerTitle.setText(backStackTitles.get(depth));
            isRestoringTitle = false;
        }
    }

public void resetHeader() {
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextSize(20);
        headerBackBtn.setVisibility(View.GONE);

        if (bellContainer != null) bellContainer.setVisibility(View.VISIBLE);
        if (bellBtn != null) bellBtn.setVisibility(View.VISIBLE);

        // Дергаем метод MainActivity для обновления бейджика уведомлений
        if (updateBadgeCallback != null) {
            updateBadgeCallback.run();
        }
    }
}
