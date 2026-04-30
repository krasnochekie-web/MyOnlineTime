package com.myonlinetime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
    private Runnable hideBgRunnable;
    
    private ViewPager2 viewPager; // Сделали полем класса для доступа в onHiddenChanged

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

        // Вызываем новый метод настройки шапки
        setupHeader(activity, startOnFollowers);

        // Находим элементы вкладок
        final TextView txtFollowers = mainLayout.findViewById(R.id.tab_txt_followers);
        final TextView txtFollowing = mainLayout.findViewById(R.id.tab_txt_following);
        final View lineFollowers = mainLayout.findViewById(R.id.tab_line_followers);
        final View lineFollowing = mainLayout.findViewById(R.id.tab_line_following);
        final TextView countFollowers = mainLayout.findViewById(R.id.tab_count_followers);
        final TextView countFollowing = mainLayout.findViewById(R.id.tab_count_following);
        
        viewPager = mainLayout.findViewById(R.id.follows_view_pager);

        // Устанавливаем адаптер
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

        // Слушаем СВАЙПЫ И КЛИКИ
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                boolean isFollowers = (position == 0);
                
                // Обновляем шапку при перелистывании
                setupHeader(activity, isFollowers);
                
                // Умная смена цвета текста
                int activeColor = ContextCompat.getColor(activity, R.color.burgundyRed);
                int inactiveColor = ContextCompat.getColor(activity, R.color.tabTextInactive);
                
                txtFollowers.setTextColor(isFollowers ? activeColor : inactiveColor);
                txtFollowing.setTextColor(!isFollowers ? activeColor : inactiveColor);

                // Обновляем остальной UI
                txtFollowers.setSelected(isFollowers);
                txtFollowing.setSelected(!isFollowers);
                lineFollowers.setSelected(isFollowers);
                lineFollowing.setSelected(!isFollowers);
                countFollowers.setBackgroundResource(isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
                countFollowing.setBackgroundResource(!isFollowers ? R.drawable.bg_badge_active : R.drawable.bg_badge_inactive);
            }
        });

        // Слушаем КЛИКИ по вкладкам
        mainLayout.findViewById(R.id.tab_followers).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewPager.setCurrentItem(0, true); }
        });
        mainLayout.findViewById(R.id.tab_following).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewPager.setCurrentItem(1, true); }
        });

        // Устанавливаем начальную вкладку
        viewPager.setCurrentItem(startOnFollowers ? 0 : 1, false);

        // Загружаем счетчики
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if(acct != null && activity.mGoogleSignInClient != null) {
            // ИСПОЛЬЗУЕМ SILENT SIGN IN И JSON ПАРСЕР
            activity.mGoogleSignInClient.silentSignIn().addOnSuccessListener(freshAccount -> {
                VpsApi.authenticateWithGoogle(activity, freshAccount.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        VpsApi.getCounts(token, targetUid, new VpsApi.Callback() {
                            @Override public void onSuccess(String result) {
                                if (!isAdded()) return;
                                try {
                                    org.json.JSONObject json = new org.json.JSONObject(result);
                                    countFollowers.setText(String.valueOf(json.optInt("followers", 0)));
                                    countFollowing.setText(String.valueOf(json.optInt("following", 0)));
                                } catch (Exception e) {}
                            }
                            @Override public void onError(String e) {}
                        });
                    }
                    @Override public void onError(String e) {}
                });
            }).addOnFailureListener(e -> {
                // Фолбэк на случай если токен не обновился тихо
                VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        VpsApi.getCounts(token, targetUid, new VpsApi.Callback() {
                            @Override public void onSuccess(String result) {
                                if (!isAdded()) return;
                                try {
                                    org.json.JSONObject json = new org.json.JSONObject(result);
                                    countFollowers.setText(String.valueOf(json.optInt("followers", 0)));
                                    countFollowing.setText(String.valueOf(json.optInt("following", 0)));
                                } catch (Exception ex) {}
                            }
                            @Override public void onError(String ex) {}
                        });
                    }
                    @Override public void onError(String ex) {}
                });
            });
        }

        return mainLayout; 
    }

    // ========================================================
    // МЕТОД ДЛЯ НАСТРОЙКИ ШАПКИ
    // ========================================================
    private void setupHeader(MainActivity activity, boolean isFollowers) {
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(activity.getString(isFollowers ? R.string.followers : R.string.following));
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        if (!hidden) {
            boolean isFollowers = (viewPager == null || viewPager.getCurrentItem() == 0);
            setupHeader(activity, isFollowers);
            
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
