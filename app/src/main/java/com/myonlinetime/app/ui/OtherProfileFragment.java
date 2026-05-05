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
import android.widget.FrameLayout;
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

    private float lastTouchX = 0;
    private float lastTouchY = 0;

    // === УМНЫЙ КЭШ ПРЕДЗАГРУЗКИ НА 15 ПРОФИЛЕЙ (ЗАЩИТА ОТ OOM) ===
    public static final android.util.LruCache<String, User> prefetchUserCache = new android.util.LruCache<>(15);
    public static final android.util.LruCache<String, String> prefetchCountsCache = new android.util.LruCache<>(15);
    public static final android.util.LruCache<String, Boolean> prefetchFollowCache = new android.util.LruCache<>(15);

    // Метод для фоновой предзагрузки из адаптера (без влияния на UI)
    public static void prefetchProfile(String vpsToken, String uid) {
        if (vpsToken == null || uid == null || uid.isEmpty()) return;
        
        if (prefetchUserCache.get(uid) == null) {
            VpsApi.getUser(null, vpsToken, uid, new VpsApi.UserCallback() {
                @Override public void onLoaded(User user) { if (user != null) prefetchUserCache.put(uid, user); }
                @Override public void onError(String error) {}
            });
        }
        if (prefetchCountsCache.get(uid) == null) {
            VpsApi.getCounts(vpsToken, uid, new VpsApi.Callback() {
                @Override public void onSuccess(String result) { prefetchCountsCache.put(uid, result); }
                @Override public void onError(String error) {}
            });
        }
        if (prefetchFollowCache.get(uid) == null) {
            VpsApi.checkIsFollowing(vpsToken, uid, new VpsApi.BooleanCallback() {
                @Override public void onResult(boolean result) { prefetchFollowCache.put(uid, result); }
            });
        }
    }

    private static class AppUiData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        String description;
        boolean isDeleted;
    }

    // === ТЕПЕРЬ МЫ ПРИНИМАЕМ БАЗОВЫЕ ДАННЫЕ ДЛЯ МГНОВЕННОГО ОТОБРАЖЕНИЯ ===
    public static OtherProfileFragment newInstance(String targetUid, String backTitle, String nickname, String about, String photo) {
        OtherProfileFragment fragment = new OtherProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        args.putString("BACK_TITLE", backTitle);
        args.putString("PREFETCH_NICKNAME", nickname != null ? nickname : "");
        args.putString("PREFETCH_ABOUT", about != null ? about : "");
        args.putString("PREFETCH_PHOTO", photo != null ? photo : "");
        fragment.setArguments(args);
        return fragment;
    }

    public OtherProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        
        final View originalView = inflater.inflate(R.layout.layout_profile, container, false);
        if (activity == null) return originalView;

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null ? originalView.getLayoutParams() : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView bgImageView = new ImageView(activity);
        bgImageView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImageView.setBackgroundColor(android.graphics.Color.parseColor("#121212")); 

        wrapper.addView(bgImageView);
        wrapper.addView(originalView);

        targetUid = getArguments() != null ? getArguments().getString("TARGET_UID", "") : "";
        backTitle = getArguments() != null ? getArguments().getString("BACK_TITLE", activity.getString(R.string.title_search)) : activity.getString(R.string.title_search);

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerManager.showBackButton(backTitle, v -> activity.onBackPressed());

        final TextView nameView = originalView.findViewById(R.id.profile_name);
        final TextView aboutView = originalView.findViewById(R.id.profile_about);
        avatarView = originalView.findViewById(R.id.profile_avatar);
        
        final View btnEdit = originalView.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = originalView.findViewById(R.id.btn_follow);
        final TextView followersCount = originalView.findViewById(R.id.txt_followers_count);
        final TextView followingCount = originalView.findViewById(R.id.txt_following_count);
        final TextView weekTimeText = originalView.findViewById(R.id.profile_week_time);
        View followersClick = originalView.findViewById(R.id.container_followers);
        View followingClick = originalView.findViewById(R.id.container_following);

        TextView tabTopApps = originalView.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);

        final ImageView btnExpand = originalView.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = originalView.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = originalView.findViewById(R.id.profile_apps_container);

        btnEdit.setVisibility(View.GONE);

        // === 1. МГНОВЕННОЕ ОТОБРАЖЕНИЕ (TELEGRAM ЭФФЕКТ) ===
        String argName = getArguments() != null ? getArguments().getString("PREFETCH_NICKNAME", "") : "";
        String argAbout = getArguments() != null ? getArguments().getString("PREFETCH_ABOUT", "") : "";
        String argPhoto = getArguments() != null ? getArguments().getString("PREFETCH_PHOTO", "") : "";

        if (!argName.isEmpty()) nameView.setText(argName);
        else nameView.setText(activity.getString(R.string.loading));

        if (!argAbout.isEmpty()) {
            aboutView.setText(argAbout);
            aboutView.setVisibility(View.VISIBLE);
        }
        if (!argPhoto.isEmpty()) handleMediaLoading(activity, argPhoto);

        // === 2. ПРОВЕРКА КЭША ПРЕДЗАГРУЗКИ ===
        User cachedUser = prefetchUserCache.get(targetUid);
        if (cachedUser != null) {
            renderOtherUserStats(cachedUser.topApps, cachedUser.totalTime, cachedUser.hiddenApps, cachedUser.appDescriptions, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);
            if (cachedUser.background != null && cachedUser.background.length() > 5) {
                Glide.with(activity).load(cachedUser.background).centerCrop().into(bgImageView);
            }
        }

        String cachedCounts = prefetchCountsCache.get(targetUid);
        if (cachedCounts != null) applyCountsJson(cachedCounts, followersCount, followingCount);

        Boolean cachedFollow = prefetchFollowCache.get(targetUid);
        if (cachedFollow != null) {
            btnFollow.setTag(cachedFollow);
            updateFollowButton(btnFollow, cachedFollow);
            btnFollow.setVisibility(View.VISIBLE);
        }

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

        btnFollow.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false; 
        });

        btnFollow.setOnClickListener(v -> {
             if (btnFollow.getTag() == null) return;
             boolean currentStatus = (boolean) btnFollow.getTag();
             boolean nextStatus = !currentStatus;
             
             btnFollow.setTag(nextStatus); 
             updateFollowButton(btnFollow, nextStatus);
             prefetchFollowCache.put(targetUid, nextStatus); // Обновляем кэш мгновенно
             
             try {
                 int count = Integer.parseInt(followersCount.getText().toString());
                 count = nextStatus ? count + 1 : count - 1;
                 if (count < 0) count = 0;
                 followersCount.setText(String.valueOf(count));
             } catch (Exception e) {}
             
             if (activity.vpsToken != null) {
                 VpsApi.setFollow(activity.vpsToken, targetUid, nextStatus, new VpsApi.Callback() {
                     @Override public void onSuccess(String s) { refreshCounts(activity); }
                     @Override public void onError(String err) {
                         if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();
                     }
                 });
             }
        });

        // === 3. ФОНОВЫЙ ЗАПРОС ДЛЯ ПОЛУЧЕНИЯ САМЫХ СВЕЖИХ ДАННЫХ (Без моргания UI) ===
        if (activity.vpsToken != null) {
            VpsApi.getUser(activity, activity.vpsToken, targetUid, new VpsApi.UserCallback() {
                @Override
                public void onLoaded(User user) {
                    if (!isAdded()) return;
                    if (user != null) {
                        prefetchUserCache.put(targetUid, user); // Обновляем кэш
                        nameView.setText(user.nickname != null ? user.nickname : activity.getString(R.string.no_name));
                        aboutView.setText(user.about != null ? user.about : "");
                        StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

                        if (user.photo != null && user.photo.length() > 5) handleMediaLoading(activity, user.photo);
                        renderOtherUserStats(user.topApps, user.totalTime, user.hiddenApps, user.appDescriptions, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);

                        if (user.background != null && user.background.length() > 5) {
                            Glide.with(activity).load(user.background).centerCrop().into(bgImageView);
                        } else {
                            bgImageView.setImageDrawable(null); 
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
                     prefetchFollowCache.put(targetUid, isFollowing);
                     btnFollow.setTag(isFollowing);
                     updateFollowButton(btnFollow, isFollowing);
                     btnFollow.setVisibility(View.VISIBLE);
                 }
            });
        }

        return wrapper; 
    }

    private void refreshCounts(MainActivity activity) {
        if (activity.vpsToken == null) return;
        VpsApi.getCounts(activity.vpsToken, targetUid, new VpsApi.Callback() {
            @Override public void onSuccess(String result) {
                if (!isAdded() || getView() == null) return; 
                prefetchCountsCache.put(targetUid, result);
                applyCountsJson(result, getView().findViewById(R.id.txt_followers_count), getView().findViewById(R.id.txt_following_count));
            }
            @Override public void onError(String error) {}
        });
    }

    private void applyCountsJson(String jsonStr, TextView followersCount, TextView followingCount) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            if (followersCount != null) followersCount.setText(String.valueOf(json.optInt("followers", 0)));
            if (followingCount != null) followingCount.setText(String.valueOf(json.optInt("following", 0)));
        } catch (Exception e) {}
    }

    private void handleMediaLoading(MainActivity activity, String photoUrl) {
        if (!isAdded() || avatarView == null) return;
        if (photoUrl == null || photoUrl.isEmpty() || photoUrl.equals("null")) {
            Glide.with(activity).load(R.drawable.bg_edit_circle).circleCrop().into(avatarView);
            return;
        }
        Glide.with(activity).load(photoUrl).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
    }

    @Override
    public void onResume() {
        super.onResume();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !isHidden()) refreshCounts(activity);
        
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
            
            if (getView() != null) {
                TextView tabTopApps = getView().findViewById(R.id.tab_top_apps);
                if (tabTopApps != null) tabTopApps.setSelected(true);
            }
        }
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
        Context ctx = btnFollow.getContext();
        if (isFollowing) {
            btnFollow.setText(ctx.getString(R.string.btn_unfollow));
            btnFollow.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textGrayDynamic));

            android.graphics.drawable.Drawable bg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_button_gray);
            if (bg != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                bg.setHotspot(lastTouchX, lastTouchY);
            }
            btnFollow.setBackground(bg);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.graphics.drawable.Drawable fg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ripple_button_gray);
                if (fg != null) {
                    fg.setHotspot(lastTouchX, lastTouchY);
                    btnFollow.setForeground(fg);
                }
            }
        } else {
            btnFollow.setText(ctx.getString(R.string.btn_follow));
            btnFollow.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textWhiteStatic));

            android.graphics.drawable.Drawable bg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_button_grapefruit);
            if (bg != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                bg.setHotspot(lastTouchX, lastTouchY);
            }
            btnFollow.setBackground(bg);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.graphics.drawable.Drawable fg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ripple_button_grapefruit);
                if (fg != null) {
                    fg.setHotspot(lastTouchX, lastTouchY);
                    btnFollow.setForeground(fg);
                }
            }
        }
    }
}
