package com.myonlinetime.app.utils;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.myonlinetime.app.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmartHeaderManager {

    private final AppCompatActivity activity;
    private final TextView headerTitle;
    private final ImageView headerBackBtn;
    private final View bellContainer;
    private final ImageView bellBtn;
    private final Runnable updateBadgeCallback;

    // СЛОВАРЬ ПАМЯТИ: Связывает конкретный фрагмент с его заголовком
    private final Map<Fragment, CharSequence> fragmentTitles = new HashMap<>();
    private boolean isRestoringTitle = false;

    public SmartHeaderManager(AppCompatActivity activity, Runnable updateBadgeCallback) {
        this.activity = activity;
        this.updateBadgeCallback = updateBadgeCallback;
        this.headerTitle = activity.findViewById(R.id.header_title);
        this.headerBackBtn = activity.findViewById(R.id.header_back_btn);
        this.bellContainer = activity.findViewById(R.id.header_bell_container);
        this.bellBtn = activity.findViewById(R.id.header_bell_btn);

        setupTitleTracker();
    }

    private void setupTitleTracker() {
        headerTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!isRestoringTitle) {
                    // Как только любой экран меняет текст шапки, мы намертво привязываем этот текст к нему
                    Fragment top = getTopFragment();
                    if (top != null) {
                        fragmentTitles.put(top, s.toString());
                    }
                }
            }
        });
    }

    // Умный поиск: находит реальный активный экран, ИГНОРИРУЯ те, которые сейчас закрываются/исчезают
    private Fragment getTopFragment() {
        List<Fragment> fragments = activity.getSupportFragmentManager().getFragments();
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Fragment f = fragments.get(i);
            // Условие !f.isRemoving() - это та самая магия, которая убивает задержку анимации!
            if (f != null && f.isAdded() && !f.isHidden() && !f.isRemoving() && f.getView() != null) {
                return f;
            }
        }
        return null;
    }

    public void updateHeaderAfterBack() {
        Fragment top = getTopFragment();
        
        // Включаем второстепенный дизайн
        headerBackBtn.setVisibility(View.VISIBLE);
        if (bellContainer != null) bellContainer.setVisibility(View.GONE);
        if (bellBtn != null) bellBtn.setVisibility(View.GONE);

        // Достаем заголовок из памяти именно для этого экрана
        if (top != null && fragmentTitles.containsKey(top)) {
            isRestoringTitle = true;
            headerTitle.setText(fragmentTitles.get(top));
            isRestoringTitle = false;
        }
    }

    public void resetHeader() {
        isRestoringTitle = true;
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextSize(20);
        isRestoringTitle = false;
        
        headerBackBtn.setVisibility(View.GONE);

        if (bellContainer != null) bellContainer.setVisibility(View.VISIBLE);
        if (bellBtn != null) bellBtn.setVisibility(View.VISIBLE);

        if (updateBadgeCallback != null) {
            updateBadgeCallback.run();
        }
    }
}
