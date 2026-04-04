package com.myonlinetime.app.utils;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmartHeaderManager {

    private final MainActivity activity;
    private final TextView headerTitle;
    private final ImageView headerBackBtn;
    private final View bellContainer;
    private final ImageView bellBtn;
    private final Runnable updateBadgeCallback;

    private final Map<String, CharSequence> fragmentTitles = new HashMap<>();
    private boolean isRestoringTitle = false;

    public SmartHeaderManager(MainActivity activity, Runnable updateBadgeCallback) {
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
                    Fragment top = getTopFragment();
                    if (top != null) {
                        fragmentTitles.put(top.getClass().getSimpleName(), s.toString());
                    }
                }
            }
        });
    }

    private Fragment getTopFragment() {
        List<Fragment> fragments = activity.getSupportFragmentManager().getFragments();
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Fragment f = fragments.get(i);
            if (f != null && f.isAdded() && !f.isHidden() && !f.isRemoving() && f.getView() != null) {
                return f;
            }
        }
        return null;
    }

    public void updateHeaderAfterBack() {
        forceRestoreCurrentSubScreen();
    }

    private void forceRestoreCurrentSubScreen() {
        headerBackBtn.setVisibility(View.VISIBLE);
        
        Fragment top = getTopFragment();
        if (top != null) {
            String className = top.getClass().getSimpleName();

            // =========================================================================
            // ЛОГИКА КОЛОКОЛЬЧИКА:
            // Прячем только если мы на экране истории уведомлений.
            // На всех остальных второстепенных экранах (подписчики и т.д.) - ПОКАЗЫВАЕМ.
            // =========================================================================
            boolean isHistoryScreen = className.contains("NotificationsHistory");
            
            int bellVisibility = isHistoryScreen ? View.GONE : View.VISIBLE;
            
            if (bellContainer != null) bellContainer.setVisibility(bellVisibility);
            if (bellBtn != null) bellBtn.setVisibility(bellVisibility);

            // Если колокольчик стал видимым, обновляем на нем цифру уведомлений
            if (!isHistoryScreen && updateBadgeCallback != null) {
                updateBadgeCallback.run();
            }

            // Восстанавливаем заголовок
            if (fragmentTitles.containsKey(className)) {
                isRestoringTitle = true;
                headerTitle.setText(fragmentTitles.get(className));
                isRestoringTitle = false;
            }
        }
    }

    public void resetHeader() {
        // Если фрагмент умирает и пытается сбросить шапку, проверяем, нет ли под ним другого саб-скрина
        if (activity.navigator != null && activity.navigator.hasSubScreen()) {
            forceRestoreCurrentSubScreen();
            return; 
        }

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
