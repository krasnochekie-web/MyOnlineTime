package com.myonlinetime.app.utils;

import android.os.SystemClock;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.myonlinetime.app.R;
import com.myonlinetime.app.ui.FeedFragment;
import com.myonlinetime.app.ui.ProfileFragment;
import com.myonlinetime.app.ui.SearchFragment;
import com.myonlinetime.app.ui.SettingsFragment; 
import com.myonlinetime.app.ui.StatsHostFragment; 

import java.util.HashMap;
import java.util.Map;

public class AppNavigator {

    private final FragmentManager fm;
    private final int containerId;

    // Главные вкладки
    private FeedFragment feedFragment; 
    private SearchFragment searchFragment;
    private StatsHostFragment statsFragment; 
    private ProfileFragment profileFragment;
    private SettingsFragment settingsFragment; 

    // Хранилище саб-скринов: теперь у КАЖДОЙ главной вкладки может быть свой саб-скрин!
    // Ключ - индекс вкладки, Значение - открытый поверх нее саб-скрин.
    private final Map<Integer, Fragment> subScreensMap = new HashMap<>();
    
    private int currentTabIndex = -1;
    
    // Защита от "Дабл-клика" (не разрешаем открывать саб-скрины чаще, чем раз в 500 мс)
    private long lastSubScreenOpenTime = 0;

    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager();
        this.containerId = containerId;

        // Восстанавливаем фрагменты
        feedFragment = (FeedFragment) fm.findFragmentByTag("FEED");
        searchFragment = (SearchFragment) fm.findFragmentByTag("SEARCH");
        statsFragment = (StatsHostFragment) fm.findFragmentByTag("STATS"); 
        profileFragment = (ProfileFragment) fm.findFragmentByTag("PROFILE");
        settingsFragment = (SettingsFragment) fm.findFragmentByTag("SETTINGS");
        
        // Восстанавливаем саб-скрины, если они были привязаны к вкладкам
        for (int i = 0; i <= 5; i++) {
            Fragment sub = fm.findFragmentByTag("SUB_SCREEN_" + i);
            if (sub != null) {
                subScreensMap.put(i, sub);
            }
        }
    }

    public void openSubScreen(Fragment fragment) {
        // ЗАЩИТА ОТ ДАБЛ-КЛИКА: Блокируем множественные вызовы подряд
        if (SystemClock.elapsedRealtime() - lastSubScreenOpenTime < 500) {
            return; 
        }
        lastSubScreenOpenTime = SystemClock.elapsedRealtime();

        // Проверяем, не открыт ли УЖЕ точно такой же класс фрагмента (двойная защита)
        Fragment currentActiveSub = subScreensMap.get(currentTabIndex);
        if (currentActiveSub != null && currentActiveSub.getClass().equals(fragment.getClass())) {
            return; // Если мы уже открыли настройки уведомлений, не открываем их поверх еще раз
        }

        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out);
        hideAll(ft);

        if (currentActiveSub != null) {
            ft.hide(currentActiveSub);
        }

        // Привязываем новый саб-скрин к ТЕКУЩЕЙ открытой вкладке
        subScreensMap.put(currentTabIndex, fragment);
        ft.add(containerId, fragment, "SUB_SCREEN_" + currentTabIndex);
        ft.commit();
    }

    public boolean closeSubScreen() {
        Fragment currentActiveSub = subScreensMap.get(currentTabIndex);
        
        if (currentActiveSub != null) {
            FragmentTransaction ft = fm.beginTransaction();
            ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
            
            // Мы не просто скрываем, мы УДАЛЯЕМ саб-скрин при закрытии (кнопка "назад")
            ft.remove(currentActiveSub); 
            subScreensMap.remove(currentTabIndex); 
            
            showMainTab(currentTabIndex, ft);
            ft.commit();
            return true;
        }
        return false;
    }

    public void switchScreen(int tabIndex, String uid) {
        // Если мы уже находимся на этой вкладке — ничего не делаем
        if (currentTabIndex == tabIndex) return;

        FragmentTransaction ft = fm.beginTransaction();

        // Анимация слайда между главными вкладками
        if (currentTabIndex != -1) {
            if (tabIndex > currentTabIndex) {
                ft.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }
        currentTabIndex = tabIndex;

        // Прячем ВСЕ главные вкладки и ВСЕ саб-скрины
        hideAll(ft);

        // ИСПРАВЛЕНИЕ: Мы БОЛЬШЕ НЕ УДАЛЯЕМ саб-скрины при переходе!
        // Проверяем, есть ли у ВЫБРАННОЙ вкладки свой открытый саб-скрин?
        Fragment savedSubScreenForTab = subScreensMap.get(tabIndex);
        
        if (savedSubScreenForTab != null) {
            // Если мы вернулись на вкладку, где был открыт саб-скрин — показываем его!
            ft.show(savedSubScreenForTab);
        } else {
            // Если саб-скрина нет — показываем главную вкладку
            showMainTab(tabIndex, ft, uid);
        }

        ft.commit();
    }

    private void hideAll(FragmentTransaction ft) {
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);
        if (settingsFragment != null) ft.hide(settingsFragment); 
        
        // Прячем все саб-скрины
        for (Fragment sub : subScreensMap.values()) {
            if (sub != null && !sub.isHidden()) {
                ft.hide(sub);
            }
        }
    }

    private void showMainTab(int index, FragmentTransaction ft, String uid) {
        if (index == 0) {
            if (feedFragment == null) {
                feedFragment = new FeedFragment(); 
                ft.add(containerId, feedFragment, "FEED");
            }
            ft.show(feedFragment);
        } 
        else if (index == 1) {
            if (searchFragment == null) {
                searchFragment = new SearchFragment();
                ft.add(containerId, searchFragment, "SEARCH");
            }
            ft.show(searchFragment);
        } 
        else if (index == 3) {
            if (statsFragment == null) {
                statsFragment = new StatsHostFragment(); 
                ft.add(containerId, statsFragment, "STATS");
            }
            ft.show(statsFragment);
        } 
        else if (index == 4) {
            if (profileFragment == null) {
                profileFragment = ProfileFragment.newInstance(uid);
                ft.add(containerId, profileFragment, "PROFILE");
            }
            ft.show(profileFragment);
        }
        else if (index == 5) {
            if (settingsFragment == null) {
                settingsFragment = new SettingsFragment();
                ft.add(containerId, settingsFragment, "SETTINGS");
            }
            ft.show(settingsFragment);
        }
    }
    
    // Перегруженный метод для closeSubScreen, когда uid не нужен
    private void showMainTab(int index, FragmentTransaction ft) {
        showMainTab(index, ft, null);
    }
}
