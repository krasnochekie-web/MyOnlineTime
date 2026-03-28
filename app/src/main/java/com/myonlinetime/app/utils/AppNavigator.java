package com.myonlinetime.app.utils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.myonlinetime.app.R;
import com.myonlinetime.app.ui.FeedFragment;
import com.myonlinetime.app.ui.ProfileFragment;
import com.myonlinetime.app.ui.SearchFragment;
import com.myonlinetime.app.ui.SettingsFragment; 
import com.myonlinetime.app.ui.StatsFragment;

public class AppNavigator {

    private final FragmentManager fm;
    private final int containerId;

    // Главные вкладки
    private FeedFragment feedFragment; 
    private SearchFragment searchFragment;
    private StatsFragment statsFragment;
    private ProfileFragment profileFragment;
    private SettingsFragment settingsFragment; 

    // Второстепенный экран (Саб-скрин)
    private Fragment currentSubScreen;
    
    private int currentTabIndex = -1;

    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager();
        this.containerId = containerId;

        // --- ЛЕКАРСТВО ОТ ФАНТОМНЫХ ФРАГМЕНТОВ ---
        // Восстанавливаем ссылки на фрагменты, если система пересоздала их после смены темы
        feedFragment = (FeedFragment) fm.findFragmentByTag("FEED");
        searchFragment = (SearchFragment) fm.findFragmentByTag("SEARCH");
        statsFragment = (StatsFragment) fm.findFragmentByTag("STATS");
        profileFragment = (ProfileFragment) fm.findFragmentByTag("PROFILE");
        settingsFragment = (SettingsFragment) fm.findFragmentByTag("SETTINGS");
        currentSubScreen = fm.findFragmentByTag("SUB_SCREEN");
        // -----------------------------------------
    }

    public void openSubScreen(Fragment fragment) {
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out);
        hideAll(ft);

        if (currentSubScreen != null) {
            ft.hide(currentSubScreen);
        }

        currentSubScreen = fragment;
        ft.add(containerId, currentSubScreen, "SUB_SCREEN");
        ft.commit();
    }

    public boolean closeSubScreen() {
        if (currentSubScreen != null) {
            FragmentTransaction ft = fm.beginTransaction();
            ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
            ft.hide(currentSubScreen); 
            currentSubScreen = null; 
            showMainTab(currentTabIndex, ft);
            ft.commit();
            return true;
        }
        return false;
    }

    public void switchScreen(int tabIndex, String uid) {
        FragmentTransaction ft = fm.beginTransaction();

        if (currentSubScreen != null) {
            ft.hide(currentSubScreen);
            currentSubScreen = null;
        }

        if (currentTabIndex != -1 && currentTabIndex != tabIndex) {
            if (tabIndex > currentTabIndex) {
                ft.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }
        currentTabIndex = tabIndex;

        hideAll(ft);

        // Показываем или создаем нужную вкладку
        if (tabIndex == 0) {
            if (feedFragment == null) {
                feedFragment = new FeedFragment(); 
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
        else if (tabIndex == 5) {
            if (settingsFragment == null) {
                settingsFragment = new SettingsFragment();
                ft.add(containerId, settingsFragment, "SETTINGS");
            }
            ft.show(settingsFragment);
        }

        ft.commit();
    }

    private void hideAll(FragmentTransaction ft) {
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);
        if (settingsFragment != null) ft.hide(settingsFragment); 
    }

    private void showMainTab(int index, FragmentTransaction ft) {
        if (index == 0 && feedFragment != null) ft.show(feedFragment);
        if (index == 1 && searchFragment != null) ft.show(searchFragment);
        if (index == 3 && statsFragment != null) ft.show(statsFragment);
        if (index == 4 && profileFragment != null) ft.show(profileFragment);
        if (index == 5 && settingsFragment != null) ft.show(settingsFragment); 
    }
            }

