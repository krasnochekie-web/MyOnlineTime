package com.myonlinetime.app.utils;

import android.os.SystemClock;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import android.view.View;

import com.myonlinetime.app.R;
import com.myonlinetime.app.ui.FeedFragment;
import com.myonlinetime.app.ui.ProfileFragment;
import com.myonlinetime.app.ui.SearchFragment;
import com.myonlinetime.app.ui.SettingsFragment; 
import com.myonlinetime.app.ui.StatsHostFragment; 

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppNavigator {

    private final FragmentManager fm;
    private final int containerId;

    private FeedFragment feedFragment; 
    private SearchFragment searchFragment;
    private StatsHostFragment statsFragment; 
    private ProfileFragment profileFragment;
    private SettingsFragment settingsFragment; 

    private final Map<Integer, List<Fragment>> subStacks = new HashMap<>();
    
    private int currentTabIndex = -1;
    private long lastSubScreenOpenTime = 0;

    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager();
        this.containerId = containerId;

        feedFragment = (FeedFragment) fm.findFragmentByTag("FEED");
        searchFragment = (SearchFragment) fm.findFragmentByTag("SEARCH");
        statsFragment = (StatsHostFragment) fm.findFragmentByTag("STATS"); 
        profileFragment = (ProfileFragment) fm.findFragmentByTag("PROFILE");
        settingsFragment = (SettingsFragment) fm.findFragmentByTag("SETTINGS");
        
        for (int tab = 0; tab <= 5; tab++) {
            List<Fragment> stack = new ArrayList<>();
            for (int depth = 0; depth < 10; depth++) {
                Fragment sub = fm.findFragmentByTag("SUB_" + tab + "_" + depth);
                if (sub != null) {
                    stack.add(sub);
                } else {
                    break;
                }
            }
            if (!stack.isEmpty()) {
                subStacks.put(tab, stack);
            }
        }
    }

    public int getCurrentTabIndex() {
        return currentTabIndex;
    }

    public void openSubScreen(Fragment fragment) {
        if (SystemClock.elapsedRealtime() - lastSubScreenOpenTime < 500) {
            return; 
        }
        lastSubScreenOpenTime = SystemClock.elapsedRealtime();

        List<Fragment> stack = subStacks.get(currentTabIndex);
        if (stack == null) {
            stack = new ArrayList<>();
            subStacks.put(currentTabIndex, stack);
        }

        if (!stack.isEmpty()) {
            Fragment currentTop = stack.get(stack.size() - 1);
            if (currentTop.getClass().equals(fragment.getClass())) {
                return;
            }
        }

        FragmentTransaction ft = fm.beginTransaction();
        
        ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out, android.R.anim.fade_in, R.anim.slide_out_down);
        hideAll(ft);

        ft.add(containerId, fragment, "SUB_" + currentTabIndex + "_" + stack.size());
        stack.add(fragment);
        
        ft.commit();
    }

    public boolean closeSubScreen() {
        List<Fragment> stack = subStacks.get(currentTabIndex);
        
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        FragmentTransaction ft = fm.beginTransaction();
        
        ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
        
        Fragment topFragment = stack.remove(stack.size() - 1);
        ft.remove(topFragment); 
        
        if (stack.isEmpty()) {
            showMainTab(currentTabIndex, ft);
        } else {
            ft.show(stack.get(stack.size() - 1));
        }
        
        ft.commit();
        return true;
    }

    public void switchScreen(int tabIndex, String uid) {
        if (currentTabIndex == tabIndex) return;

        FragmentTransaction ft = fm.beginTransaction();

        if (currentTabIndex != -1) {
            if (tabIndex > currentTabIndex || tabIndex == 3 || tabIndex == 5) {
                ft.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }
        currentTabIndex = tabIndex;

        hideAll(ft);

        List<Fragment> stack = subStacks.get(tabIndex);
        
        if (stack != null && !stack.isEmpty()) {
            Fragment target = stack.get(stack.size() - 1);
            if (target.getView() != null) target.getView().setTranslationX(0f); // Страховка от "улета"
            ft.show(target);
        } else {
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
        
        for (List<Fragment> stack : subStacks.values()) {
            for (Fragment sub : stack) {
                if (sub != null && !sub.isHidden()) {
                    ft.hide(sub);
                }
            }
        }
    }

    private void showMainTab(int index, FragmentTransaction ft, String uid) {
        if (index == 0) {
            if (feedFragment == null) {
                feedFragment = new FeedFragment(); 
                ft.add(containerId, feedFragment, "FEED");
            } else if (feedFragment.getView() != null) feedFragment.getView().setTranslationX(0f);
            ft.show(feedFragment);
        } 
        else if (index == 1) {
            if (searchFragment == null) {
                searchFragment = new SearchFragment();
                ft.add(containerId, searchFragment, "SEARCH");
            } else if (searchFragment.getView() != null) searchFragment.getView().setTranslationX(0f);
            ft.show(searchFragment);
        } 
        else if (index == 3) {
            if (statsFragment == null) {
                statsFragment = new StatsHostFragment(); 
                ft.add(containerId, statsFragment, "STATS");
            } else if (statsFragment.getView() != null) statsFragment.getView().setTranslationX(0f);
            ft.show(statsFragment);
        } 
        else if (index == 4) {
            if (profileFragment == null) {
                profileFragment = ProfileFragment.newInstance(uid);
                ft.add(containerId, profileFragment, "PROFILE");
            } else if (profileFragment.getView() != null) profileFragment.getView().setTranslationX(0f);
            ft.show(profileFragment);
        }
        else if (index == 5) {
            if (settingsFragment == null) {
                settingsFragment = new SettingsFragment();
                ft.add(containerId, settingsFragment, "SETTINGS");
            } else if (settingsFragment.getView() != null) settingsFragment.getView().setTranslationX(0f);
            ft.show(settingsFragment);
        }
    }
    
    private void showMainTab(int index, FragmentTransaction ft) {
        showMainTab(index, ft, null);
    }

    public boolean hasSubScreen() {
        List<Fragment> stack = subStacks.get(currentTabIndex);
        return stack != null && !stack.isEmpty();
    }

    // =========================================================================
    // МАГИЯ ОФФСКРИН-РЕНДЕРА С УСЫПЛЕНИЕМ (SAFE MODE)
    // Гарантирует полные списки и НЕ ЛОМАЕТ анимации переходов!
    // =========================================================================
    public void preloadProfile(String uid) {
        if (profileFragment == null) {
            profileFragment = ProfileFragment.newInstance(uid);
            
            fm.beginTransaction()
              .add(containerId, profileFragment, "PROFILE")
              .commitAllowingStateLoss();
              
            fm.executePendingTransactions(); 
            
            View view = profileFragment.getView();
            if (view != null) {
                // 1. Уносим далеко вправо. Для системы экран VISIBLE, он рисуется!
                view.setTranslationX(50000f); 
                
                // 2. Даем ровно 3.5 секунды на загрузку сервера и Glide.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (currentTabIndex != 4) { 
                        try {
                            // 3. Тихо усыпляем (без анимаций) и спасаем память
                            fm.beginTransaction().hide(profileFragment).commitAllowingStateLoss();
                            // 4. Возвращаем на место. Он спит на координате 0, готовый к анимации!
                            view.setTranslationX(0f); 
                        } catch (Exception ignored) {}
                    } else {
                        view.setTranslationX(0f);
                    }
                }, 3500);
            }
        }
    }

    public void preloadStats() {
        if (statsFragment == null) {
            statsFragment = new StatsHostFragment();
            
            fm.beginTransaction()
              .add(containerId, statsFragment, "STATS")
              .commitAllowingStateLoss();
              
            fm.executePendingTransactions(); 
            
            View view = statsFragment.getView();
            if (view != null) {
                view.setTranslationX(50000f); 
                
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (currentTabIndex != 3) { 
                        try {
                            fm.beginTransaction().hide(statsFragment).commitAllowingStateLoss();
                            view.setTranslationX(0f); 
                        } catch (Exception ignored) {}
                    } else {
                        view.setTranslationX(0f);
                    }
                }, 3500);
            }
        }
    }
}
