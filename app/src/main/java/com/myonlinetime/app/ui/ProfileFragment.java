package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.Map;

public class ProfileFragment extends Fragment {

    // Паттерн для правильного создания Фрагмента с параметрами
    public static ProfileFragment newInstance(String targetUid) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        fragment.setArguments(args);
        return fragment;
    }

    public ProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_profile, container, false);
        final MainActivity activity = (MainActivity) getActivity();

        if (activity == null) return view;

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.resetHeader();

        // Достаем ID пользователя, которого хотим показать
        String targetUid = "";
        if (getArguments() != null) {
            targetUid = getArguments().getString("TARGET_UID");
        }

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return view;

        final String myUid = account.getId();
        final boolean isMe = targetUid.equals(myUid);
        final String finalTargetUid = targetUid; // для использования внутри коллбэков

        final TextView nameView = view.findViewById(R.id.profile_name);
        final TextView aboutView = view.findViewById(R.id.profile_about);
        final ImageView avatarView = view.findViewById(R.id.profile_avatar);
        final View btnEdit = view.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = view.findViewById(R.id.btn_follow);
        final ImageView btnBack = view.findViewById(R.id.profile_back_btn);
        final TextView followersCount = view.findViewById(R.id.txt_followers_count);
        final TextView followingCount = view.findViewById(R.id.txt_following_count);
        final TextView weekTimeText = view.findViewById(R.id.profile_week_time);

        View followersClick = view.findViewById(R.id.container_followers);
        View followingClick = view.findViewById(R.id.container_following);
                // Зажигаем вкладку
        TextView tabTopApps = view.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);

        // Логика кнопок "развернуть" и "свернуть"
        final android.widget.ImageView btnExpand = view.findViewById(R.id.btn_expand_apps);
        final android.widget.ImageView btnCollapse = view.findViewById(R.id.btn_collapse_apps);
        final android.widget.LinearLayout appsContainerLocal = view.findViewById(R.id.profile_apps_container);
        
        // Нажали "развернуть"
        btnExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Показываем все
                for (int i = 0; i < appsContainerLocal.getChildCount(); i++) {
                    appsContainerLocal.getChildAt(i).setVisibility(View.VISIBLE);
                }
                btnExpand.setVisibility(View.GONE);
                btnCollapse.setVisibility(View.VISIBLE); // Показываем минус
            }
        });

        // Нажали "свернуть"
        btnCollapse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Прячем всё после третьего
                for (int i = 3; i < appsContainerLocal.getChildCount(); i++) {
                    appsContainerLocal.getChildAt(i).setVisibility(View.GONE);
                }
                btnCollapse.setVisibility(View.GONE);
                btnExpand.setVisibility(View.VISIBLE); // Возвращаем 3 полоски
            }
        });

        // Внимание: FollowsScreen пока остался старым экраном, мы его не трогали
        followersClick.setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { 
                activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, true)); 
            }
        });
        followingClick.setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { 
                activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, false)); 
            }
        });

        if (!isMe) btnBack.setVisibility(View.VISIBLE);

        if (isMe) {
            nameView.setText(activity.prefs.getString("my_nickname", account.getDisplayName()));
            aboutView.setText(activity.prefs.getString("my_about", ""));

            Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + myUid);
            if (cachedAvatar == null) {
                try {
                    File file = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                    if (file.exists()) {
                        cachedAvatar = BitmapFactory.decodeFile(file.getAbsolutePath());
                        activity.mMemoryCache.put("avatar_" + myUid, cachedAvatar);
                    }
                } catch (Exception e) {}
            }

            // СТАЛО:
if (cachedAvatar != null) {
    Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarView);
} else {
    avatarView.setBackgroundResource(R.drawable.bg_edit_circle);
}

            btnEdit.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);

            btnEdit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                            nameView.getText().toString(), 
                            aboutView.getText().toString()
                    ));
                }
            });

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isAdded()) return; // Защита от краша, если вкладку закрыли
                    LinearLayout appsContainer = view.findViewById(R.id.profile_apps_container);
                    StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainer);
                }
            }, 50);

        } else {
            nameView.setText(activity.getString(R.string.loading));
            btnEdit.setVisibility(View.GONE);
            btnFollow.setVisibility(View.INVISIBLE);
        }

        final Runnable fetchProfileData = new Runnable() {
            @Override
            public void run() {
                VpsApi.getUser(activity.vpsToken, finalTargetUid, new VpsApi.UserCallback() {
                    @Override
                    public void onLoaded(User user) {
                        if (!isAdded()) return; // Защита от краша

                        if (user != null) {
                            if (user.nickname != null) {
                                nameView.setText(user.nickname);
                                if (isMe) activity.prefs.edit().putString("my_nickname", user.nickname).apply();
                            } else {
                                nameView.setText(isMe ? account.getDisplayName() : activity.getString(R.string.no_name));
                            }

                            if (user.about != null) {
                                aboutView.setText(user.about);
                                if (isMe) activity.prefs.edit().putString("my_about", user.about).apply();
                            } else {
                                aboutView.setText("");
                            }

                            if (user.photo != null && user.photo.length() > 10) {
                                File localAvatar = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                                if (isMe && localAvatar.exists() && user.photo.startsWith("http")) {
                                    // Свой аватар уже загружен из кэша выше, ничего не делаем
                                } else {
                                    // Загружаем чужой аватар через Glide
                                    if (user.photo.startsWith("http")) {
                                        Glide.with(activity).load(user.photo).circleCrop().into(avatarView);
                                    } else {
                                        try {
                                            byte[] imageByteArray = android.util.Base64.decode(user.photo, android.util.Base64.DEFAULT);
                                            Glide.with(activity).asBitmap().load(imageByteArray).circleCrop().into(avatarView);
                                        } catch (Exception e) {}
                                    }
                                }
                            }

                            if (!isMe) {
                                long minutes = user.totalTime / 1000 / 60;
                                long hours = minutes / 60;
                                long mins = minutes % 60;
                                weekTimeText.setText(hours > 0 ? hours + " ч " + mins + " мин" : mins + " мин");
                                LinearLayout appsContainer = view.findViewById(R.id.profile_apps_container);
                                renderOtherUserStats(user.topApps, appsContainer, activity);
                            }
                        } else if (!isMe) nameView.setText(activity.getString(R.string.new_user));

                    }
                    @Override public void onError(String e) {}
                });

                VpsApi.getCounts(activity.vpsToken, finalTargetUid, new VpsApi.Callback() {
                    @Override public void onSuccess(String result) {
                        if (!isAdded()) return; 
                        if (result != null && result.contains(":")) {
                            String[] parts = result.split(":");
                            if (parts.length >= 2) {
                                followersCount.setText(parts[0]);
                                followingCount.setText(parts[1]);
                            }
                        }
                    }
                    @Override public void onError(String error) {}
                });

                if (!isMe) {
                    VpsApi.checkIsFollowing(activity.vpsToken, finalTargetUid, new VpsApi.BooleanCallback() {
                         @Override public void onResult(final boolean isFollowing) {
                             if (!isAdded()) return;
                             updateFollowButton(btnFollow, isFollowing);
                             btnFollow.setVisibility(View.VISIBLE);
                             btnFollow.setOnClickListener(new View.OnClickListener() {
                                 boolean currentStatus = isFollowing;
                                 public void onClick(View v) {
                                     currentStatus = !currentStatus;
                                     updateFollowButton(btnFollow, currentStatus);
                                     try {
                                         int count = Integer.parseInt(followersCount.getText().toString());
                                         count = currentStatus ? count + 1 : count - 1;
                                         if (count < 0) count = 0;
                                         followersCount.setText(String.valueOf(count));
                                     } catch (Exception e) {}

                                     VpsApi.setFollow(activity.vpsToken, finalTargetUid, currentStatus, new VpsApi.Callback() {
                                         @Override public void onSuccess(String s) {}
                                         @Override public void onError(String err) {
                                             if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();

                                         }
                                     });
                                 }
                             });
                         }
                    });
                }
            }
        };

        if (activity.vpsToken != null) {
            fetchProfileData.run();
        } else {
            VpsApi.authenticateWithGoogle(account.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(final String token) {
                    activity.vpsToken = token;
                    fetchProfileData.run();
                }
                @Override public void onError(String error) {}
            });
        }

        // Возвращаем готовую View, MainActivity сам добавит её на экран
        return view;
    } // Конец метода onCreateView

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                activity.resetHeader();
            }
        }
    } // Конец метода onHiddenChanged

    private void renderOtherUserStats(Map<String, Long> topApps, android.widget.LinearLayout container, MainActivity activity) {
        container.removeAllViews();
        if (topApps == null || topApps.isEmpty() || activity == null) return;
        android.content.pm.PackageManager pm = activity.getPackageManager();
        
        int limit = 0;
        for (Map.Entry<String, Long> entry : topApps.entrySet()) {
            if (limit >= 10) break;
            
            View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, container, false);
            
            // ПРЯЧЕМ
            if (limit >= 3) {
                view.setVisibility(View.GONE);
            }
            android.widget.ImageView iconView = view.findViewById(R.id.app_icon);
            android.widget.TextView nameView = view.findViewById(R.id.app_name);
            android.widget.TextView timeView = view.findViewById(R.id.app_time);
            
            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(entry.getKey(), 0);
                nameView.setText(pm.getApplicationLabel(appInfo));
                iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
            } catch (Exception e) { 
                nameView.setText(entry.getKey()); 
            }
            
            timeView.setText(Utils.formatTime(activity, entry.getValue()));
            container.addView(view);
            limit++;
        }
        
        // Показываем кнопку
        View btnExpand = ((View)container.getParent()).findViewById(R.id.btn_expand_apps);
        if (btnExpand != null) {
            btnExpand.setVisibility(limit > 3 ? View.VISIBLE : View.GONE);
        }
    } // Конец метода renderOtherUserStats

    private void updateFollowButton(android.widget.Button btnFollow, boolean isFollowing) {
        if (isFollowing) {
            btnFollow.setText(btnFollow.getContext().getString(R.string.btn_unfollow));
            btnFollow.setBackgroundResource(R.drawable.bg_button_gray);
            btnFollow.setTextColor(btnFollow.getContext().getResources().getColor(R.color.textGray));
        } else {
            btnFollow.setText("Подписаться");
            btnFollow.setBackgroundResource(R.drawable.bg_button_grapefruit);
            btnFollow.setTextColor(btnFollow.getContext().getResources().getColor(R.color.textWhite));
        }
    } // Конец метода updateFollowButton

} // Конец всего класса ProfileFragment
