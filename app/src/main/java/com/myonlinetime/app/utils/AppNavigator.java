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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // ИСПРАВЛЕНО: Теперь хранилище — это СТЕК (Список) фрагментов для каждой вкладки
    // Ключ - индекс вкладки, Значение - стопка (List) открытых саб-скринов
    private final Map<Integer, List<Fragment>> subStacks = new HashMap<>();
    
    private int currentTabIndex = -1;
    private long lastSubScreenOpenTime = 0;

    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager();
        this.containerId = containerId;

        // Восстанавливаем главные фрагменты
        feedFragment = (FeedFragment) fm.findFragmentByTag("FEED");
        searchFragment = (SearchFragment) fm.findFragmentByTag("SEARCH");
        statsFragment = (StatsHostFragment) fm.findFragmentByTag("STATS"); 
        profileFragment = (ProfileFragment) fm.findFragmentByTag("PROFILE");
        settingsFragment = (SettingsFragment) fm.findFragmentByTag("SETTINGS");
        
        // Восстанавливаем стеки саб-скринов (если система убивала приложение)
        for (int tab = 0; tab <= 5; tab++) {
            List<Fragment> stack = new ArrayList<>();
            // Ищем слои (до 10 экранов в глубину)
            for (int depth = 0; depth < 10; depth++) {
                Fragment sub = fm.findFragmentByTag("SUB_" + tab + "_" + depth);
                if (sub != null) {
                    stack.add(sub);
                } else {
                    break; // Дальше слоев нет
                }
            }
            if (!stack.isEmpty()) {
                subStacks.put(tab, stack);
            }
        }
    }

    public void openSubScreen(Fragment fragment) {
        // ЗАЩИТА ОТ ДАБЛ-КЛИКА
        if (SystemClock.elapsedRealtime() - lastSubScreenOpenTime < 500) {
            return; 
        }
        lastSubScreenOpenTime = SystemClock.elapsedRealtime();

        // Получаем стек текущей вкладки (или создаем новый, если пуст)
        List<Fragment> stack = subStacks.get(currentTabIndex);
        if (stack == null) {
            stack = new ArrayList<>();
            subStacks.put(currentTabIndex, stack);
        }

        // Двойная защита: не даем открыть поверх точно такой же экран
        if (!stack.isEmpty()) {
            Fragment currentTop = stack.get(stack.size() - 1);
            if (currentTop.getClass().equals(fragment.getClass())) {
                return;
            }
        }

        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.slide_in_up, android.R.anim.fade_out);
        
        // Прячем всё текущее
        hideAll(ft);

        // Добавляем новый фрагмент НАВЕРХ стека
        ft.add(containerId, fragment, "SUB_" + currentTabIndex + "_" + stack.size());
        stack.add(fragment);
        
        ft.commit();
    }

    public boolean closeSubScreen() {
        List<Fragment> stack = subStacks.get(currentTabIndex);
        
        // Если стек пуст, значит саб-скринов нет, возвращаем false (передаем управление MainActivity)
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(android.R.anim.fade_in, R.anim.slide_out_down);
        
        // Берем самый верхний "блин" из стопки и удаляем его
        Fragment topFragment = stack.remove(stack.size() - 1);
        ft.remove(topFragment); 
        
        // Если после удаления стопка опустела — показываем главную вкладку
        if (stack.isEmpty()) {
            showMainTab(currentTabIndex, ft);
        } else {
            // Иначе показываем предыдущий саб-скрин, который лежал ПОД удаленным
            Fragment previousFragment = stack.get(stack.size() - 1);
            ft.show(previousFragment);
        }
        
        ft.commit();
        return true;
    }

    public void switchScreen(int tabIndex, String uid) {
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

        // Проверяем стек ВЫБРАННОЙ вкладки
        List<Fragment> stack = subStacks.get(tabIndex);
        
        if (stack != null && !stack.isEmpty()) {
            // Если в стеке что-то есть, показываем самый ВЕРХНИЙ элемент
            ft.show(stack.get(stack.size() - 1));
        } else {
            // Иначе показываем главную вкладку
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
        
        // Прячем все саб-скрины во всех стеках
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
    
    // Перегруженный метод для closeSubScreen, когда uid не нужен
    private void showMainTab(int index, FragmentTransaction ft) {
        showMainTab(index, ft, null);
    }

    // Возвращает true, если в стеке текущей вкладки есть экраны
    public boolean hasSubScreen() {
        List<Fragment> stack = subStacks.get(currentTabIndex);
        return stack != null && !stack.isEmpty();
    }
}
