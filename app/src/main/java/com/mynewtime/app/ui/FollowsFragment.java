package com.mynewtime.app.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.mynewtime.app.MainActivity;
import com.mynewtime.app.R;
import com.mynewtime.app.VpsApi;
import com.mynewtime.app.models.User;
import com.mynewtime.app.utils.Utils;

import java.util.List;

public class FollowsFragment extends Fragment {

    // Паттерн для создания Фрагмента с параметрами
    public static FollowsFragment newInstance(String targetUid, boolean isFollowersTab) {
        FollowsFragment fragment = new FollowsFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        args.putBoolean("IS_FOLLOWERS_TAB", isFollowersTab);
        fragment.setArguments(args);
        return fragment;
    }

    public FollowsFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        
        // 1. Надуваем XML
        View mainLayout = inflater.inflate(R.layout.layout_follows, container, false);
        if (activity == null) return mainLayout;

        // 2. Достаем параметры
        String targetUid = "";
        boolean isFollowersTab = true;
        if (getArguments() != null) {
            targetUid = getArguments().getString("TARGET_UID", "");
            isFollowersTab = getArguments().getBoolean("IS_FOLLOWERS_TAB", true);
        }

        final String uid = targetUid;

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.resetHeader();
        activity.headerTitle.setText(isFollowersTab ? "Подписчики" : "Подписки");
        activity.headerBackBtn.setVisibility(View.VISIBLE);

        final TextView txtFollowers = mainLayout.findViewById(R.id.tab_txt_followers);
        final TextView txtFollowing = mainLayout.findViewById(R.id.tab_txt_following);
        final View lineFollowers = mainLayout.findViewById(R.id.tab_line_followers);
        final View lineFollowing = mainLayout.findViewById(R.id.tab_line_following);
        final TextView countFollowers = mainLayout.findViewById(R.id.tab_count_followers);
        final TextView countFollowing = mainLayout.findViewById(R.id.tab_count_following);

        // 3. Собираем программный ScrollView и добавляем его в mainLayout        // Находим контейнер для списка и текст статуса
        final LinearLayout listContainer = mainLayout.findViewById(R.id.follows_list_container);
        final TextView statusText = mainLayout.findViewById(R.id.follows_status_text);

        final boolean[] currentTabIsFollowers = {isFollowersTab};

        final Runnable updateTabsUI = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return; // Защита
                final boolean isFollowers = currentTabIsFollowers[0];
                
                activity.headerTitle.setText(activity.getString(isFollowers ? R.string.followers : R.string.following));
                
                countFollowers.setBackgroundResource(isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
                    txtFollowers.setSelected(isFollowers);
                    txtFollowing.setSelected(!isFollowers);
                countFollowing.setBackgroundResource(!isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
                    lineFollowers.setSelected(isFollowers);
                    lineFollowing.setSelected(!isFollowers);
                
                            listContainer.removeAllViews();
                            statusText.setVisibility(View.VISIBLE);
                            statusText.setText(activity.getString(R.string.err_loading));
                
                GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                if(acct != null) {
                    VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                        @Override
                        public void onSuccess(String token) {
                            activity.vpsToken = token;
                            VpsApi.getList(activity.vpsToken, uid, isFollowers ? "followers" : "following", new VpsApi.SearchCallback() {
                                @Override public void onFound(List<User> users) {
                                    if (!isAdded()) return; // Защита
                                    listContainer.removeAllViews();
                                    if(users == null || users.isEmpty()) {
                                        statusText.setVisibility(View.VISIBLE);
                                        statusText.setText(activity.getString(R.string.empty_list));
                                    } else {
                                        statusText.setVisibility(View.GONE); // Прячем текст, если есть пользователи
                                        for(User u : users) listContainer.addView(Utils.createSearchUserCard(activity, u));
                                    }
                                }
                            });
                        }
                        @Override
                        public void onError(String error) {
                            if (!isAdded()) return;
                            listContainer.removeAllViews();
                            statusText.setVisibility(View.VISIBLE);
                            statusText.setText(activity.getString(R.string.err_loading));
                        }
                    });
                }
            }
        };

        mainLayout.findViewById(R.id.tab_followers).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if(!currentTabIsFollowers[0]) { currentTabIsFollowers[0] = true; updateTabsUI.run(); } }
        });
        mainLayout.findViewById(R.id.tab_following).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if(currentTabIsFollowers[0]) { currentTabIsFollowers[0] = false; updateTabsUI.run(); } }
        });

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if(acct != null) {
            VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String token) {
                    activity.vpsToken = token;
                    VpsApi.getCounts(activity.vpsToken, uid, new VpsApi.Callback() {
                        @Override public void onSuccess(String result) {
                            if (!isAdded()) return;
                            if (result != null) {
                                String[] parts = result.split(":");
                                if (parts.length >= 2) {
                                    countFollowers.setText(parts[0]);
                                    countFollowing.setText(parts[1]);
                                }
                            }
                        }
                        @Override public void onError(String e) {}
                    });
                }
                @Override public void onError(String e) {}
            });
        }
        
        updateTabsUI.run();

        return mainLayout; // Возвращаем собранный экран
    }
}