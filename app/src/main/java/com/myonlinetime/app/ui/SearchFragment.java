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
import android.widget.ImageView;
import android.widget.ProgressBar;

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

    // Сколько верхних карточек префетчим жадно (полный профиль + фон),
    // чтобы фон точно лежал в кэше к моменту тапа по тем результатам,
    // что юзер увидит первыми. prefetchProfile сам отсеивает закэшированные UID.
    private static final int EAGER_TOP_K = 8;

    private String lastSearchQuery = "";
    private RecyclerView resultsList;
    private UserListAdapter adapter;
    private ProgressBar loadingSpinner;

    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    public SearchFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }

        View view = inflater.inflate(R.layout.layout_search, container, false);

        EditText searchInput = view.findViewById(R.id.search_input);
        ImageView clearBtn = view.findViewById(R.id.search_clear_btn);
        resultsList = view.findViewById(R.id.search_results_list);
        loadingSpinner = view.findViewById(R.id.search_loading_spinner);

        resultsList.setLayoutManager(new LinearLayoutManager(activity));

        GoogleSignInAccount acct = activity != null ? GoogleSignIn.getLastSignedInAccount(activity) : null;
        final String myUid = acct != null ? acct.getId() : "";

        // === ИСПРАВЛЕНИЕ: ПЕРЕДАЕМ ВЕСЬ БАГАЖ (ТОЛСТЫЙ СПИСОК) ===
        adapter = new UserListAdapter(activity, clickedUser -> {
            if (activity == null || activity.navigator == null) return;

            // === ЗАПРЕТ ПЕРЕХОДА НА СВОЙ ПРОФИЛЬ ИЗ ПОИСКА ===
            // Свой профиль из поиска открывать нет смысла — по тапу ничего не делаем.
            if (clickedUser.uid != null && !myUid.isEmpty() && clickedUser.uid.equals(myUid)) {
                return;
            }

            activity.navigator.openSubScreen(OtherProfileFragment.newInstance(
                    clickedUser.uid,
                    activity.getString(R.string.title_search),
                    clickedUser.nickname,
                    clickedUser.about,
                    clickedUser.photo,
                    clickedUser.background,    // <-- Передаем фон
                    clickedUser.followers,     // <-- Передаем подписчиков
                    clickedUser.following,     // <-- Передаем подписки
                    clickedUser.isFollowing    // <-- Передаем статус "подписан ли я"
            ));
        });

        resultsList.setAdapter(adapter);
        resultsList.setOverScrollMode(View.OVER_SCROLL_NEVER);

        clearBtn.setOnClickListener(v -> searchInput.setText(""));

        if (lastSearchQuery.length() > 0) {
            searchInput.setText(lastSearchQuery);
            searchInput.setSelection(lastSearchQuery.length());
            clearBtn.setVisibility(View.VISIBLE);
            performSearch(lastSearchQuery, activity);
        } else {
            clearBtn.setVisibility(View.GONE);
        }

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastSearchQuery = s.toString();
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> performSearch(s.toString(), activity);
                // Откладываем поиск на 400мс, чтобы не спамить API при быстром вводе
                searchHandler.postDelayed(searchRunnable, 400);
            }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() > 0) {
                    clearBtn.setVisibility(View.VISIBLE);
                } else {
                    clearBtn.setVisibility(View.GONE);
                }
            }
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

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }
    }

    private void performSearch(final String query, final MainActivity activity) {
        if (query.trim().length() > 0) {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            if (activity.vpsToken != null && !activity.vpsToken.isEmpty()) {
                executeSearchApi(activity.vpsToken, query);
            } else {
                if (activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.silentSignIn().addOnSuccessListener(freshAccount -> {
                        VpsApi.authenticateWithGoogle(activity, freshAccount.getIdToken(), new VpsApi.LoginCallback() {
                            @Override public void onSuccess(String token) { activity.vpsToken = token; executeSearchApi(token, query); }
                            @Override public void onError(String e) { if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE); }
                        });
                    }).addOnFailureListener(e -> {
                        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                        if (acct != null) {
                            VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                                @Override public void onSuccess(String token) { activity.vpsToken = token; executeSearchApi(token, query); }
                                @Override public void onError(String ex) { if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE); }
                            });
                        }
                    });
                }
            }
        } else {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            adapter.setUsers(new ArrayList<>());
        }
    }

    private void executeSearchApi(String token, String query) {
        VpsApi.searchUsers(token, query, new VpsApi.SearchCallback() {
            @Override public void onFound(List<User> users) {
                if (!isAdded()) return;
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);

                adapter.setUsers(users);

                // === Заливаем кэши "толстыми" данными из ответа списка.
                if (users != null) {
                    for (User u : users) {
                        if (u == null || u.uid == null) continue;
                        OtherProfileFragment.prefetchUserCache.put(u.uid, u);
                        try {
                            org.json.JSONObject countsObj = new org.json.JSONObject();
                            countsObj.put("followers", u.followers);
                            countsObj.put("following", u.following);
                            OtherProfileFragment.prefetchCountsCache.put(u.uid, countsObj.toString());
                        } catch (Exception ignored) {}
                        OtherProfileFragment.prefetchFollowCache.put(u.uid, u.isFollowing);
                    }
                }

                // === ИСПРАВЛЕНИЕ ПРЕДЗАГРУЗКИ ФОНОВ ===
                eagerPrefetchTopK(token, users, EAGER_TOP_K);
            }
        });
    }

    /**
     * Жадный префетч полного профиля + фона для первых K результатов поиска.
     */
    private void eagerPrefetchTopK(String token, List<User> users, int k) {
        if (token == null || token.isEmpty()) return;
        if (users == null || users.isEmpty() || k <= 0) return;

        int max = Math.min(k, users.size());
        for (int i = 0; i < max; i++) {
            User u = users.get(i);
            if (u == null || u.uid == null || u.uid.isEmpty()) continue;
            OtherProfileFragment.prefetchProfile(token, u.uid);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }
    }
}
