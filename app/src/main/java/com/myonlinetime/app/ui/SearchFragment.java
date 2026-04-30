package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.adapters.UserListAdapter;

import java.util.ArrayList;
import java.util.List;

public class SearchFragment extends Fragment {

    private String lastSearchQuery = "";
    private RecyclerView resultsList; 
    private UserListAdapter adapter;  
    
    // Переменная для отложенной задачи выключения фона
    private Runnable hideBgRunnable;

    // Таймер для задержки запроса (защита от спама на сервер)
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    public SearchFragment() {
        // Обязательный пустой конструктор
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();
        }

        View view = inflater.inflate(R.layout.layout_search, container, false);
        
        EditText searchInput = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results_list);

        // Настройка списка
        resultsList.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new UserListAdapter(activity);
        resultsList.setAdapter(adapter);
        
        // Убираем визуальный разрыв при скролле (эффект оттягивания)
        resultsList.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Восстановление поиска
        if (lastSearchQuery.length() > 0) {
            searchInput.setText(lastSearchQuery);
            searchInput.setSelection(lastSearchQuery.length());
            performSearch(lastSearchQuery, activity);
        }

        // Слушатель ввода
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastSearchQuery = s.toString();
                
                // Отменяем предыдущий запланированный запрос, если пользователь продолжает печатать
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                
                // Планируем новый запрос через 400 мс (Debounce)
                searchRunnable = () -> performSearch(s.toString(), activity);
                searchHandler.postDelayed(searchRunnable, 400);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        return view;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        if (!hidden) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();
            
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) {
                        activity.updateGlobalBackground(false);
                    }
                };
            }
            if (getView() != null) {
                getView().postDelayed(hideBgRunnable, 300);
            } else {
                activity.updateGlobalBackground(false);
            }
        } else {
            if (hideBgRunnable != null && getView() != null) {
                getView().removeCallbacks(hideBgRunnable);
            }
        }
    }

    private void performSearch(final String query, final MainActivity activity) {
        if(query.trim().length() > 0) {
            // Если токен сервера уже есть, не делаем лишний запрос авторизации!
            if (activity.vpsToken != null && !activity.vpsToken.isEmpty()) {
                executeSearchApi(activity.vpsToken, query);
            } else {
                // Токена нет, запрашиваем новый
                GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                if(acct != null) {
                    VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                        @Override
                        public void onSuccess(String token) {
                            activity.vpsToken = token;
                            executeSearchApi(token, query);
                        }
                        @Override public void onError(String e) {}
                    });
                }
            }
        } else {
            // ИСПРАВЛЕНИЕ ВЫЛЕТА: очищаем список правильно, без null
            adapter.setUsers(new ArrayList<>());
        }
    }

    private void executeSearchApi(String token, String query) {
        VpsApi.searchUsers(token, query, new VpsApi.SearchCallback() {
            @Override public void onFound(List<User> users) {
                if (!isAdded()) return; 
                adapter.setUsers(users);
            }
        });
    }

    // ========================================================
    // НАШ ПРЕДОХРАНИТЕЛЬ: Гасим фон с задержкой при входе
    // ========================================================
    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) {
                        activity.updateGlobalBackground(false);
                    }
                };
            }
            if (getView() != null) {
                getView().postDelayed(hideBgRunnable, 300);
            } else {
                activity.updateGlobalBackground(false);
            }
        }
    }
}
