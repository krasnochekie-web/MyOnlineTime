package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class OtherProfileFragment extends Fragment {

    private ImageView avatarView;
    private String targetUid = "";
    private String backTitle = "";

    private String loadedBgUrl = null;
    private boolean isBgLoaded = false;

    private static class AppUiData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        String description;
        boolean isDeleted;
    }

    public static OtherProfileFragment newInstance(String targetUid, String backTitle) {
        OtherProfileFragment fragment = new OtherProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        args.putString("BACK_TITLE", backTitle);
        fragment.setArguments(args);
        return fragment;
    }

    public OtherProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_profile, container, false);
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return view;

        targetUid = getArguments() != null ? getArguments().getString("TARGET_UID", "") : "";
        backTitle = getArguments() != null ? getArguments().getString("BACK_TITLE", activity.getString(R.string.title_search)) : activity.getString(R.string.title_search);

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerManager.showBackButton(backTitle, v -> activity.onBackPressed());

        if (activity.navigator != null && activity.navigator.getCurrentTabIndex() == 4) {
            activity.updateGlobalBackground(true);
        }

        final TextView nameView = view.findViewById(R.id.profile_name);
        final TextView aboutView = view.findViewById(R.id.profile_about);
        avatarView = view.findViewById(R.id.profile_avatar);
        
        final View btnEdit = view.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = view.findViewById(R.id.btn_follow);
        final TextView followersCount = view.findViewById(R.id.txt_followers_count);
        final TextView followingCount = view.findViewById(R.id.txt_following_count);
        final TextView weekTimeText = view.findViewById(R.id.profile_week_time);
        View followersClick = view.findViewById(R.id.container_followers);
        View followingClick = view.findViewById(R.id.container_following);

        TextView tabTopApps = view.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);

        final ImageView btnExpand = view.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = view.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = view.findViewById(R.id.profile_apps_container);

        btnEdit.setVisibility(View.GONE);
        nameView.setText(activity.getString(R.string.loading));

        btnExpand.setOnClickListener(v -> {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        btnCollapse.setOnClickListener(v -> {
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(targetUid, "followers")));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(targetUid, "following")));

        if (activity.vpsToken != null) {
            VpsApi.getUser(activity, activity.vpsToken, targetUid, new VpsApi.UserCallback() {
                @Override
                public void onLoaded(User user) {
                    if (!isAdded()) return;
                    if (user != null) {
                        nameView.setText(user.nickname != null ? user.nickname : activity.getString(R.string.no_name));
                        aboutView.setText(user.about != null ? user.about : "");
                        StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

                        if (user.photo != null && user.photo.length() > 10) handleMediaLoading(activity, user.photo);
                        renderOtherUserStats(user.topApps, user.totalTime, user.hiddenApps, user.appDescriptions, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);

                        // === МГНОВЕННОЕ ВКЛЮЧЕНИЕ ФОНА ===
                        isBgLoaded = true;
                        if (user.background != null && user.background.length() > 10) {
                            loadedBgUrl = user.background;
                            activity.previewBackground(loadedBgUrl);
                        } else {
                            loadedBgUrl = null;
                            activity.previewBackground("none"); 
                        }
                    } else {
                        nameView.setText(activity.getString(R.string.new_user));
                    }
                }
                @Override public void onError(String e) {}
            });

            refreshCounts(activity);

            VpsApi.checkIsFollowing(activity.vpsToken, targetUid, new VpsApi.BooleanCallback() {
                 @Override public void onResult(final boolean isFollowing) {
                     if (!isAdded()) return;
                     btnFollow.setTag(isFollowing);
                     updateFollowButton(btnFollow, isFollowing);
                     btnFollow.setVisibility(View.VISIBLE);
                     
                     btnFollow.setOnClickListener(v -> {
                         boolean currentStatus = btnFollow.getTag() != null && (boolean) btnFollow.getTag();
                         boolean nextStatus = !currentStatus;
                         btnFollow.setTag(nextStatus); 
                         updateFollowButton(btnFollow, nextStatus);
                         
                         try {
                             int count = Integer.parseInt(followersCount.getText().toString());
                             count = nextStatus ? count + 1 : count - 1;
                             if (count < 0) count = 0;
                             followersCount.setText(String.valueOf(count));
                         } catch (Exception e) {}
                         
                         VpsApi.setFollow(activity.vpsToken, targetUid, nextStatus, new VpsApi.Callback() {
                             @Override public void onSuccess(String s) { refreshCounts(activity); }
                             @Override public void onError(String err) {
                                 if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();
                             }
                         });
                     });
                 }
            });
        }

        return view;
    }

    private void refreshCounts(MainActivity activity) {
        if (activity.vpsToken == null) return;
        VpsApi.getCounts(activity.vpsToken, targetUid, new VpsApi.Callback() {
            @Override public void onSuccess(String result) {
                if (!isAdded() || getView() == null) return; 
                try {
                    org.json.JSONObject json = new org.json.JSONObject(result);
                    TextView followersCount = getView().findViewById(R.id.txt_followers_count);
                    TextView followingCount = getView().findViewById(R.id.txt_following_count);
                    if (followersCount != null) followersCount.setText(String.valueOf(json.optInt("followers", 0)));
                    if (followingCount != null) followingCount.setText(String.valueOf(json.optInt("following", 0)));
                } catch (Exception e) {}
            }
            @Override public void onError(String error) {}
        });
    }

    private void handleMediaLoading(MainActivity activity, String base64Data) {
        if (!isAdded() || avatarView == null) return;
        if (base64Data == null || base64Data.isEmpty()) {
            Glide.with(activity).load(R.drawable.bg_edit_circle).circleCrop().into(avatarView);
            return;
        }
        if (base64Data.startsWith("http")) {
            Glide.with(activity).load(base64Data).circleCrop().into(avatarView);
            return;
        }
        Utils.backgroundExecutor.execute(() -> {
            try {
                byte[] mediaBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    avatarView.setVisibility(View.VISIBLE);
                    Glide.with(activity).load(mediaBytes).circleCrop().into(avatarView);
                });
            } catch (Exception e) {}
        });
    }

    private String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) { return pkg; }
    }

    @Override
    public void onResume() {
        super.onResume();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !isHidden()) {
            refreshCounts(activity);
            
            if (isBgLoaded) {
                if (loadedBgUrl != null) {
                    activity.previewBackground(loadedBgUrl);
                } else {
                    activity.previewBackground("none");
                }
            }
        }
        
        if (getView() != null) {
            TextView tabTopApps = getView().findViewById(R.id.tab_top_apps);
            if (tabTopApps != null) tabTopApps.setSelected(true);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        
        if (!hidden) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.showBackButton(backTitle, v -> activity.onBackPressed());
            refreshCounts(activity);
            
            if (isBgLoaded) {
                if (loadedBgUrl != null) {
                    activity.previewBackground(loadedBgUrl);
                } else {
                    activity.previewBackground("none");
                }
            }
            
            if (getView() != null) {
                TextView tabTopApps = getView().findViewById(R.id.tab_top_apps);
                if (tabTopApps != null) tabTopApps.setSelected(true);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private void renderOtherUserStats(Map<String, Long> topApps, long serverTotalTime, List<String> hiddenAppsList, Map<String, String> appDescriptions, LinearLayout container, MainActivity activity, TextView weekTimeText, TextView aboutView, ImageView btnExpand, ImageView btnCollapse) {
        if (container != null) {
            container.setLayoutTransition(null);
            container.removeAllViews();
        }
        if (activity == null) return;

        if (topApps == null || topApps.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (container != null) container.setVisibility(View.GONE); 
                long minutes = serverTotalTime / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;
                if (weekTimeText != null) {
                    weekTimeText.setText(hours > 0 ? activity.getString(R.string.format_hours_mins, hours, mins) : activity.getString(R.string.format_mins, mins));
                }
            });
            return;
        }

        final long[] totalVisibleTime = {0};
        final List<AppUiData> preloadedData = new ArrayList<>();

        Utils.backgroundExecutor.execute(() -> {
            try {
                PackageManager pm = activity.getPackageManager();
                SharedPreferences dbNames = activity.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);

                Map<String, ?> safeTopApps = (Map<String, ?>) topApps;

                for (Map.Entry<String, ?> entry : safeTopApps.entrySet()) {
                    if (entry.getKey() == null) continue;
                    String pkgName = entry.getKey().replaceAll("\\s+", "");

                    if (hiddenAppsList != null && hiddenAppsList.contains(pkgName)) continue;

                    AppUiData data = new AppUiData();
                    data.pkgName = pkgName;
                    
                    long appTime = 0;
                    Object val = entry.getValue();
                    if (val instanceof Number) {
                        appTime = ((Number) val).longValue();
                    } else if (val != null) {
                        try { appTime = (long) Double.parseDouble(String.valueOf(val)); } catch (Exception e) {}
                    }
                    data.time = appTime;
                    
                    if (appDescriptions != null) data.description = appDescriptions.get(pkgName);
                    data.isDeleted = false;

                    ApplicationInfo appInfo = null;
                    try {
                        appInfo = pm.getApplicationInfo(pkgName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        try {
                            int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                            appInfo = pm.getApplicationInfo(pkgName, flag);
                            boolean isInstalled = (appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                            boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            if (!isInstalled && !isSystemApp) data.isDeleted = true; 
                        } catch (PackageManager.NameNotFoundException ignored) { data.isDeleted = true; }
                    }

                    String cachedName = dbNames.getString(pkgName, null);
                    if (cachedName != null) data.appName = cachedName;
                    else if (appInfo != null) data.appName = pm.getApplicationLabel(appInfo).toString();
                    else {
                        String[] parts = pkgName.split("\\.");
                        String name = parts[parts.length - 1]; 
                        data.appName = name.substring(0, 1).toUpperCase() + name.substring(1); 
                    }

                    if (appInfo != null) {
                        try { data.icon = pm.getApplicationIcon(appInfo); } catch (Exception ignored) {}
                    }

                    preloadedData.add(data);
                    totalVisibleTime[0] += data.time;
                }
                
                Collections.sort(preloadedData, new Comparator<AppUiData>() {
                    @Override
                    public int compare(AppUiData o1, AppUiData o2) {
                        return Long.compare(o2.time, o1.time);
                    }
                });
                
                if (preloadedData.size() > 10) {
                    preloadedData.subList(10, preloadedData.size()).clear();
                }
                
            } catch (Exception e) { e.printStackTrace(); }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;

                if (preloadedData.isEmpty()) {
                    if (container != null) container.setVisibility(View.GONE);
                } else {
                    if (container != null) container.setVisibility(View.VISIBLE);
                    
                    for (AppUiData data : preloadedData) {
                        View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, container, false);
                        
                        ImageView iconView = view.findViewById(R.id.app_icon);
                        TextView nameView = view.findViewById(R.id.app_name);
                        TextView timeView = view.findViewById(R.id.app_time);
                        TextView descView = view.findViewById(R.id.app_custom_description);
                        ImageView lockView = view.findViewById(R.id.app_lock_icon);
                        ImageView optionsBtn = view.findViewById(R.id.btn_app_options);
                        ImageView iconDeleted = view.findViewById(R.id.icon_deleted);

                        if (optionsBtn != null) optionsBtn.setVisibility(View.GONE);
                        if (lockView != null) lockView.setVisibility(View.GONE);

                        if (data.description != null && !data.description.isEmpty() && descView != null) {
                            descView.setText(data.description);
                            descView.setVisibility(View.VISIBLE);
                        }

                        nameView.setText(data.appName);
                        if (data.icon != null) iconView.setImageDrawable(data.icon);
                        else iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                        
                        timeView.setText(Utils.formatTime(activity, data.time));
                        
                        if (iconDeleted != null) {
                            if (data.isDeleted) {
                                iconDeleted.setVisibility(View.VISIBLE);
                                iconDeleted.setOnClickListener(v -> Toast.makeText(activity, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show());
                            } else {
                                iconDeleted.setVisibility(View.GONE);
                                iconDeleted.setOnClickListener(null);
                            }
                        }
                        if (container != null) container.addView(view);
                    }
                }

                long timeToShow = Math.max(serverTotalTime, totalVisibleTime[0]);
                long minutes = timeToShow / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;

                if (weekTimeText != null) {
                    weekTimeText.setText(hours > 0 ? activity.getString(R.string.format_hours_mins, hours, mins) : activity.getString(R.string.format_mins, mins));
                    weekTimeText.setOnClickListener(null); 
                }

                StatsHelper.applyCollapseLogic(aboutView, container, btnExpand, btnCollapse);
            });
        });
    }

    private void updateFollowButton(android.widget.Button btnFollow, boolean isFollowing) {
        if (isFollowing) {
            btnFollow.setText(btnFollow.getContext().getString(R.string.btn_unfollow));
            btnFollow.setBackgroundResource(R.drawable.bg_button_gray);
            btnFollow.setTextColor(btnFollow.getContext().getResources().getColor(R.color.textGrayDynamic));
        } else {
            btnFollow.setText(btnFollow.getContext().getString(R.string.btn_follow));
            btnFollow.setBackgroundResource(R.drawable.bg_button_grapefruit);
            btnFollow.setTextColor(btnFollow.getContext().getResources().getColor(R.color.textDynamic));
        }
    }
}
