package com.myonlinetime.app.utils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.myonlinetime.app.R;
import com.myonlinetime.app.ui.ProfileFragment;
import com.myonlinetime.app.ui.SearchFragment;
import com.myonlinetime.app.ui.StatsFragment;

public class AppNavigator {

    private final FragmentManager fm;
    private final int containerId;

    // Главные вкладки
    private Fragment feedFragment;
    private SearchFragment searchFragment;
    private StatsFragment statsFragment;
    private ProfileFragment profileFragment;

    // Второстепенный экран (Саб-скрин)
    private Fragment currentSubScreen;
    
    private int currentTabIndex = -1;

    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager();
        this.containerId = containerId;
    }

    // --- 1. ОТКРЫТИЕ РЕДАКТОРА / ПОДПИСЧИКОВ (Всплытие снизу) ---
    public void openSubScreen(Fragment fragment) {
        FragmentTransaction ft = fm.beginTransaction();
        
        // Красивое появление снизу
        ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out);

        // Прячем абсолютно все текущие экраны
        hideAll(ft);

        // Если у нас уже был какой-то саб-скрин в памяти (например, старый редактор) - прячем и его
        if (currentSubScreen != null) {
            ft.hide(currentSubScreen);
        }

        // Создаем новый
        currentSubScreen = fragment;
        ft.add(containerId, currentSubScreen, "SUB_SCREEN");
        ft.commit();
    }

    // --- 2. ЗАКРЫТИЕ РЕДАКТОРА (По кнопке НАЗАД) ---
    public boolean closeSubScreen() {
        if (currentSubScreen != null) {
            FragmentTransaction ft = fm.beginTransaction();
            
            // Анимация ухода вниз
            ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
            
            // МАГИЯ ЗДЕСЬ: Мы ПРОСТО ПРЯЧЕМ редактор, а не убиваем его!
            ft.hide(currentSubScreen); 
            currentSubScreen = null; // Забываем про него
            
            // Возвращаем на экран ту вкладку, откуда мы пришли
            showMainTab(currentTabIndex, ft);
            
            ft.commit();
            return true;
        }
        return false;
    }

    // --- 3. ПЕРЕКЛЮЧЕНИЕ ГЛАВНЫХ ВКЛАДОК (Нижнее меню) ---
    public void switchScreen(int tabIndex, String uid) {
        FragmentTransaction ft = fm.beginTransaction();

        // МАГИЯ ЗДЕСЬ: Если мы переключаемся на Ленту/Поиск, а открыт Редактор:
        // Мы ПРОСТО ПРЯЧЕМ Редактор! Никаких remove(), никаких конфликтов.
        if (currentSubScreen != null) {
            ft.hide(currentSubScreen);
            currentSubScreen = null;
        }

        // Мягкое растворение между вкладками (fade_in / fade_out)
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        currentTabIndex = tabIndex;

        // Прячем всё
        hideAll(ft);

        // Показываем или создаем нужную вкладку
        if (tabIndex == 0) {
            if (feedFragment == null) {
                feedFragment = new Fragment(); 
                ft.add(containerId, feedFragment, "FEED");
            }
            ft.show(feedFragment);
        } 
        else if (tabIndex == 1) {
            if (searchFragment == null) {
                searchFragment = new SearchFragment();
                ft.add(containerId, searchFragment, "SEARCH");
            }
            ft.show(searchFragment);
        } 
        else if (tabIndex == 3) {
            if (statsFragment == null) {
                statsFragment = new StatsFragment();
                ft.add(containerId, statsFragment, "STATS");
            }
            ft.show(statsFragment);
        } 
        else if (tabIndex == 4) {
            if (profileFragment == null) {
                profileFragment = ProfileFragment.newInstance(uid);
                ft.add(containerId, profileFragment, "PROFILE");
            }
            ft.show(profileFragment);
        }

        ft.commit();
    }

    // --- Вспомогательный метод: Прячет ВСЁ на экране ---
    private void hideAll(FragmentTransaction ft) {
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);
    }

    // --- Вспомогательный метод: Показывает конкретную вкладку ---
    private void showMainTab(int index, FragmentTransaction ft) {
        if (index == 0 && feedFragment != null) ft.show(feedFragment);
        if (index == 1 && searchFragment != null) ft.show(searchFragment);
        if (index == 3 && statsFragment != null) ft.show(statsFragment);
        if (index == 4 && profileFragment != null) ft.show(profileFragment);
    }
}