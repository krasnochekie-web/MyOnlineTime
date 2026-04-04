package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.os.Bundle;
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

import java.util.List;

public class SearchFragment extends Fragment {

    private String lastSearchQuery = "";
    private RecyclerView resultsList; 
    private UserListAdapter adapter;  
    
    // Переменная для отложенной задачи выключения фона
    private Runnable hideBgRunnable;

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
                performSearch(s.toString(), activity);
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
            
            // Плавное отключение: ждем 300 мс, пока пройдет анимация перехода
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    // Проверяем, что фрагмент всё ещё открыт
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
            // Если мы быстро ушли с экрана поиска до окончания анимации, 
            // отменяем команду на отключение фона, чтобы не сломать профиль
            if (hideBgRunnable != null && getView() != null) {
                getView().removeCallbacks(hideBgRunnable);
            }
        }
    }

    private void performSearch(final String query, final MainActivity activity) {
        if(query.length() > 1) {
            GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
            if(acct != null) {
                // ИСПРАВЛЕН ВЫЗОВ: Добавлен activity для работы с ресурсами внутри VpsApi
                VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        activity.vpsToken = token;
                        VpsApi.searchUsers(token, query, new VpsApi.SearchCallback() {
                            @Override public void onFound(List<User> users) {
                                if (!isAdded()) return; 
                                adapter.setUsers(users);
                            }
                        });
                    }
                    @Override public void onError(String e) {}
                });
            }
        } else {
            adapter.setUsers(null);
        }
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
