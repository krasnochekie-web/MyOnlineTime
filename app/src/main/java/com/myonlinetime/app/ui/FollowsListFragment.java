package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.adapters.UserListAdapter;
import com.myonlinetime.app.models.User;

import java.util.List;

public class FollowsListFragment extends Fragment {

    private String targetUid;
    private String listType; // "followers" или "following"
    private UserListAdapter adapter;
    private TextView statusText;
    private ProgressBar loadingSpinner;

    public static FollowsListFragment newInstance(String uid, String type) {
        FollowsListFragment f = new FollowsListFragment();
        Bundle args = new Bundle();
        args.putString("UID", uid);
        args.putString("TYPE", type);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_follows_list, container, false);
        
        targetUid = getArguments() != null ? getArguments().getString("UID") : "";
        listType = getArguments() != null ? getArguments().getString("TYPE") : "";

        RecyclerView recyclerView = view.findViewById(R.id.follows_results_list);
        statusText = view.findViewById(R.id.follows_status_text);
        loadingSpinner = view.findViewById(R.id.follows_loading_spinner);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        final MainActivity activity = (MainActivity) getActivity();
        GoogleSignInAccount acct = activity != null ? GoogleSignIn.getLastSignedInAccount(activity) : null;
        final String myUid = acct != null ? acct.getId() : "";
// Адаптер со слушателем и передачей ВСЕХ параметров
        adapter = new UserListAdapter(activity, clickedUser -> {
            if (activity != null && activity.navigator != null) {
                if (clickedUser.uid != null && clickedUser.uid.equals(myUid)) {
                    activity.navigator.switchScreen(4, myUid);
                } else {
                    String currentTitle = listType.equals("followers") ? 
                            getString(R.string.followers) : getString(R.string.following);
                            
                    // === ИСПРАВЛЕНИЕ: ПЕРЕДАЕМ "ТОЛСТЫЕ" ДАННЫЕ ===
                    activity.navigator.openSubScreen(OtherProfileFragment.newInstance(
                            clickedUser.uid, 
                            currentTitle,
                            clickedUser.nickname,
                            clickedUser.about,
                            clickedUser.photo,
                            clickedUser.background,    // <-- Передаем фон
                            clickedUser.followers,     // <-- Передаем подписчиков
                            clickedUser.following,     // <-- Передаем подписки
                            clickedUser.isFollowing    // <-- Передаем статус "подписан ли я"
                    ));
                }
            }
        });        
        recyclerView.setAdapter(adapter);

        loadData();

        return view;
    }

    private void loadData() {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        statusText.setVisibility(View.GONE);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct != null) {
            // Быстрое использование токена, если он уже есть в памяти
            if (activity.vpsToken != null && !activity.vpsToken.isEmpty()) {
                fetchList(activity.vpsToken);
            } else {
                VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        activity.vpsToken = token;
                        fetchList(token);
                    }
                    @Override public void onError(String error) {
                        if (!isAdded()) return;
                        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(getString(R.string.err_loading));
                    }
                });
            }
        } else {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(getString(R.string.err_loading));
        }
    }

    private void fetchList(String token) {
        VpsApi.getList(token, targetUid, listType, new VpsApi.SearchCallback() {
            @Override public void onFound(List<User> users) {
                if (!isAdded()) return;
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                if (users == null || users.isEmpty()) {
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText(getString(R.string.empty_list));
                } else {
                    statusText.setVisibility(View.GONE);
                    adapter.setUsers(users);
                    
                    // === ФОНОВАЯ ПРЕДЗАГРУЗКА КНОПОК И ЦИФР ПРОФИЛЕЙ ===
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null && activity.vpsToken != null) {
                        for (User u : users) {
                            OtherProfileFragment.prefetchProfile(activity.vpsToken, u.uid);
                        }
                    }
                }
            }
        });
    }

    // НИКАКИХ onResume И updateHeader ЗДЕСЬ БОЛЬШЕ НЕТ. РАБОТАЕМ СТРОГО ВНУТРИ VIEWPAGER2.
}
