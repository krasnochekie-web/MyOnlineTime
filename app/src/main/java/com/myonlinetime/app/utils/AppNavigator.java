package com.myonlinetime.app.utils;

import android.os.SystemClock;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.myonlinetime.app.R;
import com.myonlinetime.app.ui.FeedFragment;
import com.myonlinetime.app.ui.ProfileFragment;
import com.myonlinetime.app.ui.OtherProfileFragment; 
import com.myonlinetime.app.ui.SearchFragment;
import com.myonlinetime.app.ui.SettingsFragment; 
import com.myonlinetime.app.ui.StatsHostFragment; 
import com.myonlinetime.app.ui.NotificationsHistoryFragment;
import com.myonlinetime.app.ui.FollowsFragment;
import com.myonlinetime.app.ui.NotificationsFragment;

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

    // === Визуальное расположение кнопок на нижней панели ===
    // Порядок: Feed(0), Search(1), Profile(4), Time(3), Settings(5)
    private int getVisualIndex(int tabIndex) {
        switch(tabIndex) {
            case 0: return 0; // Лента
            case 1: return 1; // Поиск
            case 4: return 2; // Наш профиль (ВИЗУАЛЬНО он левее Времени)
            case 3: return 3; // Время (Usage)
            case 5: return 4; // Настройки
            default: return tabIndex;
        }
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
        
        // === Эти экраны ВСЕГДА выезжают справа ===
        if (fragment instanceof OtherProfileFragment || 
            fragment instanceof NotificationsHistoryFragment || 
            fragment instanceof FollowsFragment ||
            fragment instanceof NotificationsFragment) {
            ft.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right);
        } else {
            // Все остальные экраны выезжают снизу
            ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out, android.R.anim.fade_in, R.anim.slide_out_down);
        }
        
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
        
        Fragment topFragment = stack.get(stack.size() - 1);
        
        // === Эти экраны ВСЕГДА уезжают вправо ===
        if (topFragment instanceof OtherProfileFragment || 
            topFragment instanceof NotificationsHistoryFragment || 
            topFragment instanceof FollowsFragment ||
            topFragment instanceof NotificationsFragment) {
            ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
        } else {
            ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
        }
        
        stack.remove(stack.size() - 1);
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
            // Математика переходов опирается на ВИЗУАЛЬНЫЙ индекс
            int visualTarget = getVisualIndex(tabIndex);
            int visualCurrent = getVisualIndex(currentTabIndex);
            
            if (visualTarget > visualCurrent) {
                ft.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }
        currentTabIndex = tabIndex;

        hideAll(ft);

        List<Fragment> stack = subStacks.get(tabIndex);
        
        if (stack != null && !stack.isEmpty()) {
            ft.show(stack.get(stack.size() - 1));
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
    
    private void showMainTab(int index, FragmentTransaction ft) {
        showMainTab(index, ft, null);
    }

    public boolean hasSubScreen() {
        List<Fragment> stack = subStacks.get(currentTabIndex);
        return stack != null && !stack.isEmpty();
    }

    public void preloadProfile(String uid) {
        if (profileFragment == null) {
            profileFragment = ProfileFragment.newInstance(uid);
            
            fm.beginTransaction()
              .add(containerId, profileFragment, "PROFILE")
              .commitAllowingStateLoss();
              
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    fm.beginTransaction().hide(profileFragment).commitAllowingStateLoss();
                } catch (Exception ignored) {}
            });
        }
    }

    public void preloadStats() {
        if (statsFragment == null) {
            statsFragment = new StatsHostFragment();
            
            fm.beginTransaction()
              .add(containerId, statsFragment, "STATS")
              .commitAllowingStateLoss();
              
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    fm.beginTransaction().hide(statsFragment).commitAllowingStateLoss();
                } catch (Exception ignored) {}
            });
        }
    }
}
