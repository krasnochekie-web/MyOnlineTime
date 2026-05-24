package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class OtherProfileFragment extends Fragment {

    private ImageView avatarView;
    private ImageView bgImageView; 
    private String targetUid = "";
    private String backTitle = "";

    private float lastTouchX = 0;
    private float lastTouchY = 0;
    
    private long renderGeneration = 0;
    private long fragmentCreationTime = 0;
    
    private ProgressBar listSpinner;
    
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public static final android.util.LruCache<String, User> prefetchUserCache = new android.util.LruCache<>(15);
    public static final android.util.LruCache<String, String> prefetchCountsCache = new android.util.LruCache<>(15);
    public static final android.util.LruCache<String, Boolean> prefetchFollowCache = new android.util.LruCache<>(15);

    // === НОВЫЕ ПЕРЕМЕННЫЕ ДЛЯ МГНОВЕННОЙ ОТРИСОВКИ ===
    private String prefetchBg = "";
    private int prefetchFollowers = 0;
    private int prefetchFollowing = 0;
    private boolean prefetchIsFollowing = false;

    public static void prefetchProfile(String vpsToken, String uid) {
        if (vpsToken == null || uid == null || uid.isEmpty()) return;
        
        // Если чего-то нет в кэше, делаем один агрегированный запрос
        if (prefetchUserCache.get(uid) == null || prefetchCountsCache.get(uid) == null || prefetchFollowCache.get(uid) == null) {
            VpsApi.getAggregatedProfile(null, vpsToken, uid, new VpsApi.AggregatedProfileCallback() {
                @Override
                public void onLoaded(User user, int followers, int following, boolean isFollowing) {
                    if (user != null) {
                        prefetchUserCache.put(uid, user);
                        try {
                            org.json.JSONObject countsObj = new org.json.JSONObject();
                            countsObj.put("followers", followers);
                            countsObj.put("following", following);
                            prefetchCountsCache.put(uid, countsObj.toString());
                        } catch (Exception ignored) {}
                        prefetchFollowCache.put(uid, isFollowing);
                    }
                }
                @Override public void onError(String error) {}
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

    // === ИСПРАВЛЕНИЕ: ПРИНИМАЕМ "ТОЛСТЫЕ" ДАННЫЕ ===
    public static OtherProfileFragment newInstance(String targetUid, String backTitle, String nickname, String about, String photo, String background, int followers, int following, boolean isFollowing) {
        OtherProfileFragment fragment = new OtherProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        args.putString("BACK_TITLE", backTitle);
        args.putString("PREFETCH_NICKNAME", nickname != null ? nickname : "");
        args.putString("PREFETCH_ABOUT", about != null ? about : "");
        args.putString("PREFETCH_PHOTO", photo != null ? photo : "");
        args.putString("PREFETCH_BG", background != null ? background : "");
        args.putInt("PREFETCH_FOLLOWERS", followers);
        args.putInt("PREFETCH_FOLLOWING", following);
        args.putBoolean("PREFETCH_IS_FOLLOWING", isFollowing);
        fragment.setArguments(args);
        return fragment;
    }

    // Для совместимости со старыми вызовами (например, из списка подписчиков)
    public static OtherProfileFragment newInstance(String targetUid, String backTitle, String nickname, String about, String photo) {
        return newInstance(targetUid, backTitle, nickname, about, photo, "", 0, 0, false);
    }

    public OtherProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        fragmentCreationTime = System.currentTimeMillis(); 
        
        final MainActivity activity = (MainActivity) getActivity();
        
        final View originalView = inflater.inflate(R.layout.layout_profile, container, false);
        if (activity == null) return originalView;

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null ? originalView.getLayoutParams() : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bgImageView = new ImageView(activity);
        bgImageView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        
        // СТАВИМ ЩИТ: Сразу заливаем фон цветом приложения, чтобы чужие фоны не просвечивали снизу
        bgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));

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
        
        final TextView weekTimeText = originalView.findViewById(R.id.profile_week_time);
        View followersClick = originalView.findViewById(R.id.container_followers);
        View followingClick = originalView.findViewById(R.id.container_following);

        TextView tabTopApps = originalView.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) {
            tabTopApps.setVisibility(View.VISIBLE); 
            tabTopApps.setSelected(true); 
        }
        
        final ImageView btnExpand = originalView.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = originalView.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = originalView.findViewById(R.id.profile_apps_container);
        
        final View appsCardParent = (View) appsContainerLocal.getParent();

        ViewGroup grandParent = (ViewGroup) appsCardParent.getParent();
        int cardIndex = grandParent.indexOfChild(appsCardParent);
        grandParent.removeView(appsCardParent);
        
        FrameLayout listWrapper = new FrameLayout(activity);
        listWrapper.setLayoutParams(appsCardParent.getLayoutParams());
        appsCardParent.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listWrapper.addView(appsCardParent);
        
        listSpinner = new ProgressBar(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            listSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.grapefruit)));
        }
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                (int)(50 * getResources().getDisplayMetrics().density), 
                (int)(50 * getResources().getDisplayMetrics().density)
        );
        sp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        sp.topMargin = (int)(20 * getResources().getDisplayMetrics().density); 
        listSpinner.setLayoutParams(sp);
        listSpinner.setVisibility(View.VISIBLE); 
        
        listWrapper.addView(listSpinner);
        grandParent.addView(listWrapper, cardIndex);

        btnEdit.setVisibility(View.GONE);

        appsContainerLocal.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) { updateEmptyState(); }
            @Override
            public void onChildViewRemoved(View parent, View child) { updateEmptyState(); }
            
            private void updateEmptyState() {
                uiHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    boolean hasApps = appsContainerLocal.getChildCount() > 0;
                    if (appsCardParent != null) {
                        appsCardParent.setVisibility(hasApps ? View.VISIBLE : View.GONE);
                    } else {
                        appsContainerLocal.setVisibility(hasApps ? View.VISIBLE : View.GONE);
                    }
                    if (!hasApps) {
                        btnExpand.setVisibility(View.GONE);
                        btnCollapse.setVisibility(View.GONE);
                    }
                    
                    if (hasApps && listSpinner != null) {
                        listSpinner.setVisibility(View.GONE);
                    }
                });
            }
        });
        
        boolean initiallyHasApps = appsContainerLocal.getChildCount() > 0;
        if (appsCardParent != null) {
            appsCardParent.setVisibility(initiallyHasApps ? View.VISIBLE : View.GONE);
        } else {
            appsContainerLocal.setVisibility(initiallyHasApps ? View.VISIBLE : View.GONE);
        }
        
        if (!initiallyHasApps) {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.GONE);
        }

        // === ИСПРАВЛЕНИЕ: ЧИТАЕМ ВСЕ "ТОЛСТЫЕ" ДАННЫЕ ===
        String argName = getArguments() != null ? getArguments().getString("PREFETCH_NICKNAME", "") : "";
        String argAbout = getArguments() != null ? getArguments().getString("PREFETCH_ABOUT", "") : "";
        String argPhoto = getArguments() != null ? getArguments().getString("PREFETCH_PHOTO", "") : "";
        prefetchBg = getArguments() != null ? getArguments().getString("PREFETCH_BG", "") : "";
        prefetchFollowers = getArguments() != null ? getArguments().getInt("PREFETCH_FOLLOWERS", 0) : 0;
        prefetchFollowing = getArguments() != null ? getArguments().getInt("PREFETCH_FOLLOWING", 0) : 0;
        prefetchIsFollowing = getArguments() != null ? getArguments().getBoolean("PREFETCH_IS_FOLLOWING", false);

        // Проверяем кэш на всякий случай
        User cachedUser = prefetchUserCache.get(targetUid);
        if (cachedUser != null) {
            if (cachedUser.nickname != null && !cachedUser.nickname.isEmpty()) argName = cachedUser.nickname;
            if (cachedUser.about != null) argAbout = cachedUser.about;
            if (cachedUser.photo != null && !cachedUser.photo.isEmpty()) argPhoto = cachedUser.photo;
            if (cachedUser.background != null) prefetchBg = cachedUser.background;
        }

        // === МГНОВЕННАЯ ОТРИСОВКА ИНТЕРФЕЙСА ===
        
        if (!argName.isEmpty()) nameView.setText(argName);
        else nameView.setText(activity.getString(R.string.loading));

        if (!argAbout.trim().isEmpty()) {
            aboutView.setText(argAbout);
            aboutView.setVisibility(View.VISIBLE);
        } else {
            aboutView.setText("");
            aboutView.setVisibility(View.GONE); 
        }
        
        if (!argPhoto.isEmpty()) handleMediaLoading(activity, argPhoto);
        
        // 1. Мгновенно рисуем фон (без ожидания API)
        if (!prefetchBg.isEmpty()) updateBackgroundFromPrefs(activity, prefetchBg);

        // 2. Мгновенно ставим кнопку Подписаться в нужное положение
        btnFollow.setTag(prefetchIsFollowing);
        updateFollowButton(btnFollow, prefetchIsFollowing);
        btnFollow.setVisibility(View.VISIBLE);
        
        // 3. Мгновенно ставим цифры подписчиков
        TextView txtFollowersCount = originalView.findViewById(R.id.txt_followers_count);
        TextView txtFollowingCount = originalView.findViewById(R.id.txt_following_count);
        if (txtFollowersCount != null) txtFollowersCount.setText(String.valueOf(prefetchFollowers));
        if (txtFollowingCount != null) txtFollowingCount.setText(String.valueOf(prefetchFollowing));

        applyCollapseSafely(aboutView, appsContainerLocal, btnExpand, btnCollapse);

        if (cachedUser != null) {
            renderOtherUserStats(cachedUser.topApps, cachedUser.totalTime, cachedUser.hiddenApps, cachedUser.appDescriptions, cachedUser.resolvedNames, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);
        }

        btnExpand.setOnClickListener(v -> {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
            applyCollapseSafely(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        btnCollapse.setOnClickListener(v -> {
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
            applyCollapseSafely(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(targetUid, true)));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(targetUid, false)));

        btnFollow.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false; 
        });

        btnFollow.setOnClickListener(v -> {
             if (btnFollow.getTag() == null || !btnFollow.isEnabled()) return;
             btnFollow.setEnabled(false); 
             
             boolean currentStatus = (boolean) btnFollow.getTag();
             boolean nextStatus = !currentStatus;
             
             btnFollow.setTag(nextStatus); 
             updateFollowButton(btnFollow, nextStatus);
             prefetchFollowCache.put(targetUid, nextStatus); 
             
             try {
                 if (txtFollowersCount != null) {
                     int count = Integer.parseInt(txtFollowersCount.getText().toString());
                     count = nextStatus ? count + 1 : count - 1;
                     if (count < 0) count = 0;
                     txtFollowersCount.setText(String.valueOf(count));
                 }
             } catch (Exception e) {}
             
             if (activity.vpsToken != null) {
                 VpsApi.setFollow(activity.vpsToken, targetUid, nextStatus, new VpsApi.Callback() {
                     @Override public void onSuccess(String s) { 
                         uiHandler.post(() -> { 
                             if (isAdded() && btnFollow != null) btnFollow.setEnabled(true); 
                             
                             GoogleSignInAccount myAcc = GoogleSignIn.getLastSignedInAccount(activity);
                             if (myAcc != null) {
                                 prefetchCountsCache.remove(myAcc.getId());
                                 activity.sendBroadcast(new Intent("ACTION_PROFILE_UPDATED").setPackage(activity.getPackageName()));
                             }
                         });
                     }
                     @Override public void onError(String err) {
                         uiHandler.post(() -> {
                             if (isAdded()) {
                                 btnFollow.setEnabled(true);
                                 btnFollow.setTag(currentStatus);
                                 updateFollowButton(btnFollow, currentStatus);
                                 prefetchFollowCache.put(targetUid, currentStatus);
                                 try {
                                     if (txtFollowersCount != null) {
                                         int c = Integer.parseInt(txtFollowersCount.getText().toString());
                                         c = currentStatus ? c + 1 : c - 1;
                                         if (c < 0) c = 0;
                                         txtFollowersCount.setText(String.valueOf(c));
                                     }
                                 } catch(Exception e){}
                                 Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();
                             }
                         });
                     }
                 });
             }
        });

        // === ТИХИЙ ФОНОВЫЙ ЗАПРОС ДЛЯ ТОПА ПРИЛОЖЕНИЙ ===
        Runnable dataLoader = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                MainActivity act = (MainActivity) getActivity();
                if (act == null) return;

                if (act.vpsToken == null) {
                    uiHandler.postDelayed(this, 500); 
                    return;
                }

                VpsApi.getAggregatedProfile(act, act.vpsToken, targetUid, new VpsApi.AggregatedProfileCallback() {
                    @Override
                    public void onLoaded(User user, int followers, int following, boolean isFollowing) {
                        if (!isAdded()) return;
                        
                        // 1. Тихо обновляем счетчики и подписки (если они изменились за секунду)
                        if (txtFollowersCount != null) txtFollowersCount.setText(String.valueOf(followers));
                        if (txtFollowingCount != null) txtFollowingCount.setText(String.valueOf(following));
                        
                        try {
                            org.json.JSONObject countsObj = new org.json.JSONObject();
                            countsObj.put("followers", followers);
                            countsObj.put("following", following);
                            prefetchCountsCache.put(targetUid, countsObj.toString());
                        } catch (Exception ignored) {}

                        prefetchFollowCache.put(targetUid, isFollowing);
                        btnFollow.setTag(isFollowing);
                        updateFollowButton(btnFollow, isFollowing);

                        // 2. Обрабатываем данные профиля и рисуем топ приложений
                        if (user != null) {
                            prefetchUserCache.put(targetUid, user); 
                            nameView.setText(user.nickname != null ? user.nickname : act.getString(R.string.no_name));
                            
                            aboutView.setText(user.about != null ? user.about : "");
                            if (user.about == null || user.about.trim().isEmpty()) {
                                aboutView.setVisibility(View.GONE);
                            } else {
                                aboutView.setVisibility(View.VISIBLE);
                            }
                            
                            applyCollapseSafely(aboutView, appsContainerLocal, btnExpand, btnCollapse);

                            if (user.photo != null && user.photo.length() > 5) handleMediaLoading(act, user.photo);
                            
                            // Это то, ради чего мы делаем этот запрос - приложения!
                            renderOtherUserStats(user.topApps, user.totalTime, user.hiddenApps, user.appDescriptions, user.resolvedNames, appsContainerLocal, act, weekTimeText, aboutView, btnExpand, btnCollapse);

                            updateBackgroundFromPrefs(act, user.background); 
                        } else {
                            nameView.setText(act.getString(R.string.new_user));
                            if (listSpinner != null) listSpinner.setVisibility(View.GONE);
                        }
                    }
                    @Override public void onError(String e) {
                        if (listSpinner != null) listSpinner.setVisibility(View.GONE);
                    }
                });
            }
        };
        
        uiHandler.postDelayed(dataLoader, 200); 

        return wrapper; 
    }

    private void updateBackgroundFromPrefs(MainActivity activity, String bgUrl) {
        if (bgImageView == null || !isAdded()) return;
        
        SharedPreferences appPrefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGlobalEnabled = appPrefs.getBoolean("bg_global_enabled", true);
        boolean isOthersEnabled = appPrefs.getBoolean("bg_others_profile_enabled", true);
        boolean isOthersImages = appPrefs.getBoolean("bg_others_images_enabled", true);
        boolean isOthersGifs = appPrefs.getBoolean("bg_others_gifs_enabled", true);
        
        boolean allowBg = true;
        if (!isGlobalEnabled || !isOthersEnabled || bgUrl == null || bgUrl.length() < 5) allowBg = false;
        else {
             boolean isGif = bgUrl.toLowerCase().endsWith(".gif");
             if (isGif && !isOthersGifs) allowBg = false;
             if (!isGif && !isOthersImages) allowBg = false;
        }

        if (allowBg) {
            Glide.with(activity).load(bgUrl).centerCrop().into(bgImageView);
        } else {
            bgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));
        }
    }

    private void applyCollapseSafely(TextView aboutView, LinearLayout container, ImageView btnExpand, ImageView btnCollapse) {
        boolean isAboutEmpty = aboutView == null || aboutView.getText().toString().trim().isEmpty();
        
        if (isAboutEmpty && aboutView != null) {
            aboutView.setVisibility(View.GONE);
        }

        StatsHelper.applyCollapseLogic(aboutView, container, btnExpand, btnCollapse);
        
        if (isAboutEmpty && aboutView != null) {
            aboutView.setVisibility(View.GONE);
            aboutView.clearAnimation(); 
        }

        if (container != null && container.getChildCount() == 0) {
            View parent = (View) container.getParent();
            if (parent != null) parent.setVisibility(View.GONE);
            else container.setVisibility(View.GONE);
            
            if (btnExpand != null) btnExpand.setVisibility(View.GONE);
            if (btnCollapse != null) btnCollapse.setVisibility(View.GONE);
        }
    }

    // Этот метод больше не нужен для инициализации, но я оставил его на всякий случай
    private void applyCountsJson(String jsonStr) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            if (getView() != null) {
                TextView txtFollowersCount = getView().findViewById(R.id.txt_followers_count);
                TextView txtFollowingCount = getView().findViewById(R.id.txt_following_count);
                if (json.has("followers") && !json.isNull("followers")) {
                    int followers = json.optInt("followers", -1);
                    if (followers >= 0 && txtFollowersCount != null) txtFollowersCount.setText(String.valueOf(followers));
                }
                if (json.has("following") && !json.isNull("following")) {
                    int following = json.optInt("following", -1);
                    if (following >= 0 && txtFollowingCount != null) txtFollowingCount.setText(String.valueOf(following));
                }
            }
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
        if (activity != null && !isHidden()) {
            User cachedUser = prefetchUserCache.get(targetUid);
            if (cachedUser != null) updateBackgroundFromPrefs(activity, cachedUser.background);
        }
        
        if (getView() != null) {
            TextView tabTopApps = getView().findViewById(R.id.tab_top_apps);
            if (tabTopApps != null) {
                tabTopApps.setVisibility(View.VISIBLE);
                tabTopApps.setSelected(true);
            }
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
            
            User cachedUser = prefetchUserCache.get(targetUid);
            if (cachedUser != null) updateBackgroundFromPrefs(activity, cachedUser.background);
            
            if (getView() != null) {
                TextView tabTopApps = getView().findViewById(R.id.tab_top_apps);
                if (tabTopApps != null) {
                    tabTopApps.setVisibility(View.VISIBLE);
                    tabTopApps.setSelected(true);
                }
            }
        }
    }

    private void renderOtherUserStats(Map<String, Long> topApps, long serverTotalTime, List<String> hiddenAppsList, Map<String, String> appDescriptions, Map<String, String> resolvedNames, LinearLayout container, MainActivity activity, TextView weekTimeText, TextView aboutView, ImageView btnExpand, ImageView btnCollapse) {
        final long myGen = ++renderGeneration;

        if (topApps == null || topApps.isEmpty()) {
            uiHandler.post(() -> {
                if (myGen != renderGeneration || !isAdded()) return;
                
                if (listSpinner != null) listSpinner.setVisibility(View.GONE);
                
                if (container != null) {
                    container.removeAllViews();
                    View parent = (View) container.getParent();
                    if (parent != null) parent.setVisibility(View.GONE);
                    else container.setVisibility(View.GONE);
                }
                if (btnExpand != null) btnExpand.setVisibility(View.GONE);
                if (btnCollapse != null) btnCollapse.setVisibility(View.GONE);
                
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

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
                    
                    data.isDeleted = (appTime == 0);

                    ApplicationInfo appInfo = null;
                    try {
                        appInfo = pm.getApplicationInfo(pkgName, 0);
                    } catch (PackageManager.NameNotFoundException ignored) {}

                    String cachedName = dbNames.getString(pkgName, null);
                    if (cachedName != null) {
                        data.appName = cachedName;
                    } else if (appInfo != null) {
                        data.appName = pm.getApplicationLabel(appInfo).toString();
                    } else if (resolvedNames != null && resolvedNames.containsKey(pkgName)) {
                        data.appName = resolvedNames.get(pkgName);
                    } else {
                        data.appName = formatDeletedAppName(pkgName); 
                    }

                    if (appInfo != null) {
                        try { data.icon = pm.getApplicationIcon(appInfo); } catch (Exception ignored) {}
                    }

                    preloadedData.add(data);
                    totalVisibleTime[0] += data.time;
                }
                
                Collections.sort(preloadedData, new Comparator<AppUiData>() {
                    @Override
                    public int compare(AppUiData o1, AppUiData o2) { return Long.compare(o2.time, o1.time); }
                });
                
                if (preloadedData.size() > 10) {
                    preloadedData.subList(10, preloadedData.size()).clear();
                }
                
            } catch (Exception e) { e.printStackTrace(); }

            long elapsed = System.currentTimeMillis() - fragmentCreationTime;
            long delay = Math.max(0, 350 - elapsed); 

            uiHandler.postDelayed(() -> {
                if (!isAdded() || myGen != renderGeneration) return;

                if (container != null) {
                    container.setLayoutTransition(null);
                    container.removeAllViews();
                }

                if (preloadedData.isEmpty()) {
                    if (container != null) {
                        View parent = (View) container.getParent();
                        if (parent != null) parent.setVisibility(View.GONE);
                        else container.setVisibility(View.GONE);
                    }
                    if (btnExpand != null) btnExpand.setVisibility(View.GONE);
                    if (btnCollapse != null) btnCollapse.setVisibility(View.GONE);
                } else {
                    if (container != null) {
                        View parent = (View) container.getParent();
                        if (parent != null) parent.setVisibility(View.VISIBLE);
                        else container.setVisibility(View.VISIBLE);
                    }
                    
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
                        
                        if (data.icon != null) {
                            iconView.setImageDrawable(data.icon);
                        } else {
                            String iconUrl = "https://api.krasnocraft.ru/icons/" + data.pkgName + ".png";
                            Glide.with(activity)
                                 .load(iconUrl)
                                 .placeholder(android.R.drawable.sym_def_app_icon)
                                 .error(android.R.drawable.sym_def_app_icon)
                                 .into(iconView);
                        }
                        
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
                    applyCollapseSafely(aboutView, container, btnExpand, btnCollapse);
                }

                long timeToShow = Math.max(serverTotalTime, totalVisibleTime[0]);
                long minutes = timeToShow / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;

                if (weekTimeText != null) {
                    weekTimeText.setText(hours > 0 ? activity.getString(R.string.format_hours_mins, hours, mins) : activity.getString(R.string.format_mins, mins));
                    weekTimeText.setOnClickListener(null); 
                }
                
                if (listSpinner != null) {
                    listSpinner.setVisibility(View.GONE);
                }
            }, delay); 
        });
    }

    private String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) { return pkg; }
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
