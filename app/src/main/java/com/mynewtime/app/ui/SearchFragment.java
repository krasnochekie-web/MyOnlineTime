package com.mynewtime.app.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.mynewtime.app.MainActivity;
import com.mynewtime.app.R;
import com.mynewtime.app.VpsApi;
import com.mynewtime.app.models.User;
import com.mynewtime.app.utils.Utils;

import java.util.List;

public class SearchFragment extends Fragment {

    // Состояние поиска теперь хранится внутри Фрагмента
    private String lastSearchQuery = "";
    private LinearLayout resultsList;

    // Обязательный пустой конструктор
    public SearchFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        // 1. Надуваем наш новый XML-файл
        View view = inflater.inflate(R.layout.layout_search, container, false);
        
        // 2. Находим наши элементы по их ID из XML
        EditText searchInput = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results_list);

        // 3. Восстанавливаем старый поиск (если он был)
        if (lastSearchQuery.length() > 0) {
            searchInput.setText(lastSearchQuery);
            searchInput.setSelection(lastSearchQuery.length());
            performSearch(lastSearchQuery, resultsList, activity);
        }

        // 4. Слушаем ввод текста
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastSearchQuery = s.toString();
                performSearch(s.toString(), resultsList, activity);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Возвращаем готовую View!
        return view;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            // Когда пользователь возвращается на эту вкладку, обновляем заголовок
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                activity.resetHeader();
            }
        }
    }

    private void performSearch(final String query, final LinearLayout resultsList, final MainActivity activity) {
        if(query.length() > 1) {
            GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
            if(acct != null) {
                VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        activity.vpsToken = token;
                        VpsApi.searchUsers(token, query, new VpsApi.SearchCallback() {
                            @Override public void onFound(List<User> users) {
                                // Защита: проверяем, жив ли Фрагмент, пока шли данные с сервера
                                if (!isAdded()) return; 

                                resultsList.removeAllViews();
                                if (users != null) {
                                    for(final User u : users) {
                                        resultsList.addView(Utils.createSearchUserCard(activity, u));
                                    }
                                }
                            }
                        });
                    }
                    @Override public void onError(String e) {}
                });
            }
        } else {
            resultsList.removeAllViews();
        }
    }
}