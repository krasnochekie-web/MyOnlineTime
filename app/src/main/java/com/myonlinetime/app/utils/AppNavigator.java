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
    public AppNavigator(AppCompatActivity activity, int containerId) {
        this.fm = activity.getSupportFragmentManager(); // НОВЫЙ СОВРЕМЕННЫЙ МЕТОД
        this.containerId = containerId;
    }

    // Тот самый метод переключения, который мы убрали из MainActivity
    // Метод для открытия "вложенных" экранов (поверх остальных)
    public void openSubScreen(Fragment fragment) {
        FragmentTransaction ft = fm.beginTransaction();
        
        // МАГИЯ АНИМАЦИИ ДЛЯ ДОЧЕРНИХ ЭКРАНОВ (Всплытие снизу)
        // Эти анимации сработают на команду ft.add и ft.remove
        ft.setCustomAnimations(
            R.anim.slide_in_up,      // Как появляется новый саб-скрин
            android.R.anim.fade_out, // Как уходит старый (если был)
            android.R.anim.fade_in,  // (Для бекстека, нам тут не нужно)
            R.anim.slide_out_down    // Как саб-скрин будет уезжать вниз при закрытии
        );

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
        
        // Для возврата из редактора (sub-screen) на главные вкладки,
        // мы тоже применяем анимацию, чтобы редактор КРАСИВО УЕХАЛ ВНИЗ
        if (currentSubScreen != null) {
            // Ставим анимацию только на удаление (уход вниз)
            ft.setCustomAnimations(0, R.anim.slide_out_down);
            ft.remove(currentSubScreen);
            currentSubScreen = null;
        } else {
            // Для переключения между самими нижними вкладками
            // используем стандартное, едва заметное растворение (Fade)
            // Это сделает переход мягким, но не будет "мотать" экраны туда-сюда
            ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        }

        // 1. Прячем все текущие экраны
        if (feedFragment != null) ft.hide(feedFragment);
        if (searchFragment != null) ft.hide(searchFragment);
        if (statsFragment != null) ft.hide(statsFragment);
        if (profileFragment != null) ft.hide(profileFragment);

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