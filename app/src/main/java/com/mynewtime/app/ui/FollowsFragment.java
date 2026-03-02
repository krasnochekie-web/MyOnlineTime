package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

public class FollowsFragment extends Fragment {

    private String targetUid = "";
    private boolean startOnFollowers = true;

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
        View mainLayout = inflater.inflate(R.layout.layout_follows, container, false);
        if (activity == null) return mainLayout;

        if (getArguments() != null) {
            targetUid = getArguments().getString("TARGET_UID", "");
            startOnFollowers = getArguments().getBoolean("IS_FOLLOWERS_TAB", true);
        }

        // Настройка шапки
        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.resetHeader();
        activity.headerBackBtn.setVisibility(View.VISIBLE);

        // Находим элементы вкладок
        final TextView txtFollowers = mainLayout.findViewById(R.id.tab_txt_followers);
        final TextView txtFollowing = mainLayout.findViewById(R.id.tab_txt_following);
        final View lineFollowers = mainLayout.findViewById(R.id.tab_line_followers);
        final View lineFollowing = mainLayout.findViewById(R.id.tab_line_following);
        final TextView countFollowers = mainLayout.findViewById(R.id.tab_count_followers);
        final TextView countFollowing = mainLayout.findViewById(R.id.tab_count_following);
        
        // Находим ViewPager2
        final ViewPager2 viewPager = mainLayout.findViewById(R.id.follows_view_pager);

        // Устанавливаем адаптер (он создает два фрагмента: один для подписчиков, другой для подписок)
        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                if (position == 0) return FollowsListFragment.newInstance(targetUid, "followers");
                else return FollowsListFragment.newInstance(targetUid, "following");
            }
            @Override
            public int getItemCount() { return 2; }
        });

        // Слушаем СВАЙПЫ
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                boolean isFollowers = (position == 0);
                
                // Обновляем шапку
                activity.headerTitle.setText(activity.getString(isFollowers ? R.string.followers : R.string.following));
                
                // Обновляем UI ваших кастомных вкладок
                txtFollowers.setSelected(isFollowers);
                txtFollowing.setSelected(!isFollowers);
                lineFollowers.setSelected(isFollowers);
                lineFollowing.setSelected(!isFollowers);
                countFollowers.setBackgroundResource(isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
                countFollowing.setBackgroundResource(!isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
            }
        });

        // Слушаем КЛИКИ по вкладкам (заставляем ViewPager свайпнуться)
        mainLayout.findViewById(R.id.tab_followers).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewPager.setCurrentItem(0, true); }
        });
        mainLayout.findViewById(R.id.tab_following).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewPager.setCurrentItem(1, true); }
        });

        // Устанавливаем начальную вкладку
        viewPager.setCurrentItem(startOnFollowers ? 0 : 1, false);

        // Загружаем только циферки (счетчики)
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if(acct != null) {
            VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String token) {
                    VpsApi.getCounts(token, targetUid, new VpsApi.Callback() {
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

        return mainLayout; 
    }
}
