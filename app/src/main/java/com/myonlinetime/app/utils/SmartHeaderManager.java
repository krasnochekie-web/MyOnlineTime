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

    private final MainActivity activity; // Изменили на MainActivity, чтобы иметь доступ к навигатору
    private final TextView headerTitle;
    private final ImageView headerBackBtn;
    private final View bellContainer;
    private final ImageView bellBtn;
    private final Runnable updateBadgeCallback;

    // Память: привязываем названия к конкретным классам фрагментов
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
                        // Запоминаем, какое название установил этот фрагмент
                        fragmentTitles.put(top.getClass().getSimpleName(), s.toString());
                    }
                }
            }
        });
    }

    // Ищет реальный видимый фрагмент, игнорируя те, которые сейчас в процессе анимации закрытия
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
        if (bellContainer != null) bellContainer.setVisibility(View.GONE);
        if (bellBtn != null) bellBtn.setVisibility(View.GONE);

        // Ищем фрагмент, который остался на экране, и достаем ЕГО заголовок из памяти
        Fragment top = getTopFragment();
        if (top != null && fragmentTitles.containsKey(top.getClass().getSimpleName())) {
            isRestoringTitle = true;
            headerTitle.setText(fragmentTitles.get(top.getClass().getSimpleName()));
            isRestoringTitle = false;
        }
    }

    public void resetHeader() {
        // =========================================================================
        // АБСОЛЮТНАЯ ЗАЩИТА ОТ "УМИРАЮЩИХ" ФРАГМЕНТОВ
        // Если фрагмент перед смертью просит сбросить шапку, но навигатор говорит, 
        // что у нас ЕСТЬ открытый второстепенный экран -> ИГНОРИРУЕМ СБРОС!
        // =========================================================================
        if (activity.navigator != null && activity.navigator.hasSubScreen()) {
            forceRestoreCurrentSubScreen();
            return; 
        }

        // Если второстепенных экранов реально нет — честно сбрасываем шапку
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
