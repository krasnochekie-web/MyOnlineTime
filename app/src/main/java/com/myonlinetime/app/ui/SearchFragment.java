package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

// Импортируем новые крутые классы
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.adapters.UserListAdapter; // Наш новый адаптер

import java.util.List;

public class SearchFragment extends Fragment {

    private String lastSearchQuery = "";
    private RecyclerView resultsList; 
    private UserListAdapter adapter;  

    public SearchFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.resetHeader();
        }

        // Подгружаем дизайн из XML
        View view = inflater.inflate(R.layout.layout_search, container, false);
        
        EditText searchInput = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results_list);

        // Настраиваем RecyclerView (говорим ему быть вертикальным списком)
        resultsList.setLayoutManager(new LinearLayoutManager(activity));

        // Подключаем Адаптер
        adapter = new UserListAdapter(activity);
        resultsList.setAdapter(adapter);

        // Восстанавливаем старый поиск при повороте/возврате
        if (lastSearchQuery.length() > 0) {
            searchInput.setText(lastSearchQuery);
            searchInput.setSelection(lastSearchQuery.length());
            performSearch(lastSearchQuery, activity);
        }

        // Слушаем ввод текста
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
        if (!hidden) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                activity.resetHeader();
                // ИСПРАВЛЕНИЕ: Мы удалили мгновенное выключение фона отсюда.
                // Экрану достаточно того, что находится в onResume!
            }
        }
    }

    private void performSearch(final String query, final MainActivity activity) {
        if(query.length() > 1) {
            GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
            if(acct != null) {
                VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
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
            adapter.setUsers(null); // Очищаем список, если текст стерли
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }

}
