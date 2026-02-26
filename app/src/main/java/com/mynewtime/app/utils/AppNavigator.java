package com.mynewtime.app.utils;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;

import com.mynewtime.app.R;
import com.mynewtime.app.ui.ProfileFragment;
import com.mynewtime.app.ui.SearchFragment;
import com.mynewtime.app.ui.StatsFragment;

public class AppNavigator {

    private FragmentManager fm;
    private int containerId;

    // Наши Фрагменты теперь живут здесь, а не в MainActivity
    private Fragment feedFragment;
    private Fragment searchFragment;
    private Fragment statsFragment;
    private Fragment profileFragment;
    // Для экранов Редактирования и Подписчиков
    private Fragment currentSubScreen;

    // Конструктор: передаем Activity и ID контейнера, где будем рисовать экраны
    public AppNavigator(Activity activity, int containerId) {
        this.fm = activity.getFragmentManager();
        this.containerId = containerId;
    }

    // Тот самый метод переключения, который мы убрали из MainActivity
    // Метод для открытия "вложенных" экранов (поверх остальных)
    public void openSubScreen(Fragment fragment) {
        FragmentTransaction ft = fm.beginTransaction();
        
        // Прячем основные вкладки
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);

        // Если уже был открыт какой-то саб-скрин, удаляем его
        if (currentSubScreen != null) {
            ft.remove(currentSubScreen);
        }
        
        currentSubScreen = fragment;
        ft.add(containerId, currentSubScreen, "SUB_SCREEN");
        ft.commit();
    }
    public void switchScreen(int tabIndex, String uid) {
        FragmentTransaction ft = fm.beginTransaction();

        // 1. Прячем все текущие экраны
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);
        // Уничтожаем саб-скрин, если мы возвращаемся на главные вкладки
        if (currentSubScreen != null) {
            ft.remove(currentSubScreen);
            currentSubScreen = null;
        }

        // 2. Показываем нужный Фрагмент
        if (tabIndex == 0) {
            if (feedFragment == null) {
                feedFragment = new Fragment(); // Заглушка для ленты
                ft.add(containerId, feedFragment, "FEED");
            }
            ft.show(feedFragment);
            
        } else if (tabIndex == 1) {
            if (searchFragment == null) {
                searchFragment = new SearchFragment();
                ft.add(containerId, searchFragment, "SEARCH");
            }
            ft.show(searchFragment);
            
        } else if (tabIndex == 3) {
            if (statsFragment == null) {
                statsFragment = new StatsFragment();
                ft.add(containerId, statsFragment, "STATS");
            }
            ft.show(statsFragment);
            
        } else if (tabIndex == 4) {
            if (profileFragment == null) {
                profileFragment = ProfileFragment.newInstance(uid);
                ft.add(containerId, profileFragment, "PROFILE");
            }
            ft.show(profileFragment);
        }

        ft.commit();
    }
}