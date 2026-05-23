package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.Dialog;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ProfileFragment extends Fragment {

    private Set<String> localHiddenApps = new HashSet<>();
    private Map<String, String> localDescriptions = new HashMap<>();
    private SharedPreferences prefs;
    private final Gson gson = new Gson();
    
    private ImageView avatarView;
    private ImageView myBgImageView; 
    private String myUid = "";

    private TextView txtFollowersCount;
    private TextView txtFollowingCount;

    private View profileContentView;
    
    private ProgressBar listSpinner;

    private String currentLoadedAvatar = null;
    private String currentLoadedBg = null;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable loadMyStatsRunnable;
    private Runnable fetchProfileDataRunnable; 

    private final android.content.BroadcastReceiver profileUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && isAdded()) {
                String action = intent.getAction();
                if ("ACTION_PROFILE_UPDATED".equals(action)) {
                    updateUiFromPrefs(activity);
                } else if ("ACTION_EDIT_PROFILE_OPENED".equals(action)) {
                    if (profileContentView != null) profileContentView.setVisibility(View.INVISIBLE);
                } else if ("ACTION_EDIT_PROFILE_CLOSED".equals(action)) {
                    if (profileContentView != null) profileContentView.setVisibility(View.VISIBLE);
                }
            }
        }
    };

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
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return new View(requireContext());

        FrameLayout wrapper = new FrameLayout(requireContext());
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        
        myBgImageView = new ImageView(requireContext());
        myBgImageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        myBgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        
        // СТАВИМ ЩИТ: Заливаем фон цветом приложения, чтобы не было Франкенштейнов
        myBgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(requireContext(), R.color.bgDynamic)));

        final View originalView = inflater.inflate(R.layout.layout_profile, wrapper, false);
        profileContentView = originalView;
        
        wrapper.addView(myBgImageView, 0); 
        wrapper.addView(originalView, 1);  

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return wrapper;
        myUid = account.getId();

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerManager.resetHeader();

        prefs = activity.getSharedPreferences("MyOnlineTime_Cache_" + myUid, Context.MODE_PRIVATE);

        avatarView = originalView.findViewById(R.id.profile_avatar);
        final View btnEdit = originalView.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = originalView.findViewById(R.id.btn_follow);
        final TextView weekTimeText = originalView.findViewById(R.id.profile_week_time);
        View followersClick = originalView.findViewById(R.id.container_followers);
        View followingClick = originalView.findViewById(R.id.container_following);
        
        txtFollowersCount = originalView.findViewById(R.id.txt_followers_count);
        txtFollowingCount = originalView.findViewById(R.id.txt_following_count);

        TextView tabTopApps = originalView.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true); 
        
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
        listSpinner.setVisibility(View.GONE);
        
        listWrapper.addView(listSpinner);
        grandParent.addView(listWrapper, cardIndex);

        if (weekTimeText != null) {
            weekTimeText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (listSpinner != null) listSpinner.setVisibility(View.GONE);
                }
            });
        }

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
                });
            }
        });
        
        boolean initiallyHasApps = appsContainerLocal.getChildCount() > 0;
        if (appsCardParent != null) appsCardParent.setVisibility(initiallyHasApps ? View.VISIBLE : View.GONE);
        else appsContainerLocal.setVisibility(initiallyHasApps ? View.VISIBLE : View.GONE);
        if (!initiallyHasApps) {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.GONE);
        }

        btnFollow.setVisibility(View.GONE);
        btnEdit.setVisibility(View.VISIBLE);

        loadMyStatsRunnable = () -> {
            if (isAdded() && appsContainerLocal != null && weekTimeText != null) {
                StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
                uiHandler.postDelayed(() -> {
                    if (listSpinner != null) listSpinner.setVisibility(View.GONE);
                }, 3000);
            } else {
                if (listSpinner != null) listSpinner.setVisibility(View.GONE);
            }
        };

        btnExpand.setOnClickListener(v -> {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(originalView.findViewById(R.id.profile_about), appsContainerLocal, btnExpand, btnCollapse);
        });

        btnCollapse.setOnClickListener(v -> {
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(originalView.findViewById(R.id.profile_about), appsContainerLocal, btnExpand, btnCollapse);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(myUid, true)));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(myUid, false)));

        btnEdit.setOnClickListener(v -> {
            TextView nameView = originalView.findViewById(R.id.profile_name);
            TextView aboutView = originalView.findViewById(R.id.profile_about);
            activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                    nameView.getText().toString().equals("...") ? "" : nameView.getText().toString(),
                    aboutView.getText().toString()
            ));
        });

        // === ИСПРАВЛЕНИЕ: ИСПОЛЬЗУЕМ АГРЕГИРОВАННЫЙ ЭНДПОИНТ ===
        fetchProfileDataRunnable = () -> {
            if (activity.vpsToken == null) return;
            
            VpsApi.getAggregatedProfile(activity, activity.vpsToken, myUid, new VpsApi.AggregatedProfileCallback() {
                @Override
                public void onLoaded(User user, int followers, int following, boolean isFollowing) {
                    if (!isAdded()) return;

                    // 1. Обновляем счетчики подписок (теперь это приходит сразу)
                    if (txtFollowersCount != null) txtFollowersCount.setText(String.valueOf(followers));
                    if (txtFollowingCount != null) txtFollowingCount.setText(String.valueOf(following));
                    
                    // Кэшируем счетчики, чтобы не дергать API при возврате
                    try {
                        org.json.JSONObject countsObj = new org.json.JSONObject();
                        countsObj.put("followers", followers);
                        countsObj.put("following", following);
                        OtherProfileFragment.prefetchCountsCache.put(myUid, countsObj.toString());
                    } catch (Exception ignored) {}

                    // 2. Обрабатываем данные профиля
                    long activeTicket = activity.prefs.getLong("active_upload_ticket", 0);
                    if (activeTicket != 0 && (System.currentTimeMillis() - activeTicket < 60000)) {
                        return; 
                    }

                    if (user != null) {
                        if (user.nickname != null) activity.prefs.edit().putString("my_nickname", user.nickname).apply();
                        if (user.about != null) activity.prefs.edit().putString("my_about", user.about).apply();

                        if (user.photo != null && user.photo.length() > 10) {
                            activity.prefs.edit().putString("my_photo_base64", user.photo).apply();
                            activity.updateAvatarInUI();
                        } else {
                            String currentPhoto = activity.prefs.getString("my_photo_base64", "");
                            if (currentPhoto != null && !currentPhoto.isEmpty() && !currentPhoto.equals("null")) {
                                activity.prefs.edit().remove("my_photo_base64").remove("custom_avatar_path_" + myUid).apply();
                                File f = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                                if (f.exists()) f.delete();
                                activity.updateAvatarInUI();
                            }
                        }

                        if (user.background != null && user.background.length() > 10) {
                            String currentBg = activity.prefs.getString("my_bg_base64", "");
                            if (!currentBg.startsWith(user.background)) {
                                activity.prefs.edit().putString("my_bg_base64", user.background).apply();
                                activity.syncMyBackground(user.background);
                            }
                        } else {
                            String currentBg = activity.prefs.getString("my_bg_base64", "");
                            if (currentBg != null && !currentBg.isEmpty() && !currentBg.equals("null")) {
                                File dir = activity.getFilesDir();
                                File[] files = dir.listFiles();
                                if (files != null) {
                                    for (File f : files) {
                                        if (f.getName().startsWith("my_bg_" + myUid)) f.delete();
                                    }
                                }
                                activity.prefs.edit()
                                    .remove("custom_bg_path_" + myUid)
                                    .remove("custom_bg_is_video_" + myUid)
                                    .remove("synced_bg_url_" + myUid)
                                    .remove("my_bg_base64")
                                    .apply();
                                
                                activity.currentBgBase64 = null;
                                activity.runOnUiThread(() -> activity.updateGlobalBackground(true));
                            }
                        }

                        if (user.createdAt != null) {
                            try {
                                java.text.SimpleDateFormat serverFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                                serverFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                                java.text.SimpleDateFormat appFormat = new java.text.SimpleDateFormat("dd MMMM yyyy", new java.util.Locale("ru"));
                                java.util.Date date = serverFormat.parse(user.createdAt);
                                activity.prefs.edit().putString("my_created_at", appFormat.format(date)).apply();
                            } catch (Exception e) {}
                        }

                        boolean cacheChanged = false;
                        if (user.hiddenApps != null) {
                            Set<String> newHidden = new HashSet<>(user.hiddenApps);
                            if (!localHiddenApps.equals(newHidden)) {
                                localHiddenApps.clear();
                                localHiddenApps.addAll(newHidden);
                                prefs.edit().putStringSet("hidden_apps", localHiddenApps).apply();
                                cacheChanged = true;
                            }
                        }
                        if (user.appDescriptions != null) {
                            if (!localDescriptions.equals(user.appDescriptions)) {
                                localDescriptions.clear();
                                localDescriptions.putAll(user.appDescriptions);
                                prefs.edit().putString("app_descriptions", gson.toJson(localDescriptions)).apply();
                                cacheChanged = true;
                            }
                        }

                        updateUiFromPrefs(activity);
                        if (cacheChanged) requestLoadMyStats(true); 
                    }
                }
                @Override public void onError(String e) {}
            });
        };

        loadLocalCacheAsync(() -> {
            if (isAdded()) requestLoadMyStats(true); 
        });

        updateUiFromPrefs(activity);
        
        // Пытаемся быстро подгрузить счетчики из кэша
        String cachedCounts = OtherProfileFragment.prefetchCountsCache.get(myUid);
        if (cachedCounts != null) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(cachedCounts);
                if (json.has("followers")) txtFollowersCount.setText(String.valueOf(json.getInt("followers")));
                if (json.has("following")) txtFollowingCount.setText(String.valueOf(json.getInt("following")));
            } catch (Exception ignored) {}
        }
        
        // Запускаем агрегированный запрос
        if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();

        return wrapper; 
    }

    private void updateUiFromPrefs(MainActivity activity) {
        if (getView() == null || !isAdded()) return;
        TextView nameView = getView().findViewById(R.id.profile_name);
        TextView aboutView = getView().findViewById(R.id.profile_about);
        LinearLayout appsContainerLocal = getView().findViewById(R.id.profile_apps_container);
        ImageView btnExpand = getView().findViewById(R.id.btn_expand_apps);
        ImageView btnCollapse = getView().findViewById(R.id.btn_collapse_apps);

        nameView.setText(activity.prefs.getString("my_nickname", "..."));
        aboutView.setText(activity.prefs.getString("my_about", ""));
        StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

        String b64 = activity.prefs.getString("my_photo_base64", null);
        handleMediaLoading(activity, b64, true, myUid);

        // Читаем тумблеры из настроек
        SharedPreferences appPrefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGlobalEnabled = appPrefs.getBoolean("bg_global_enabled", true);
        boolean isMyProfileEnabled = appPrefs.getBoolean("bg_my_profile_enabled", true);
        boolean isMyImagesEnabled = appPrefs.getBoolean("bg_my_images_enabled", true);
        boolean isMyGifsEnabled = appPrefs.getBoolean("bg_my_gifs_enabled", true);

        String bgPath = activity.prefs.getString("custom_bg_path_" + myUid, null);
        String remoteBg = activity.prefs.getString("my_bg_base64", null);
        
        String targetPath = null;
        if (bgPath != null && new File(bgPath).exists()) targetPath = bgPath;
        else if (remoteBg != null && remoteBg.startsWith("http")) targetPath = remoteBg;

        // Проверяем разрешен ли фон
        boolean allowBg = true;
        if (!isGlobalEnabled || !isMyProfileEnabled || targetPath == null) allowBg = false;
        else {
            boolean isGif = targetPath.toLowerCase().endsWith(".gif");
            if (isGif && !isMyGifsEnabled) allowBg = false;
            if (!isGif && !isMyImagesEnabled) allowBg = false;
        }

        String newBgKey;
        if (!allowBg) {
            newBgKey = "disabled";
        } else if (targetPath.equals(bgPath)) {
            newBgKey = myUid + "_local_bg_" + new File(bgPath).lastModified();
        } else {
            newBgKey = myUid + "_remote_bg_" + (remoteBg != null ? String.valueOf(remoteBg.hashCode()) : "empty");
        }

        if (!newBgKey.equals(currentLoadedBg)) {
            currentLoadedBg = newBgKey;
            
            if (!allowBg) {
                myBgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));
            } else if (targetPath.equals(bgPath)) {
                if (bgPath.toLowerCase().endsWith(".gif")) {
                    Glide.with(this).load(new File(bgPath))
                         .diskCacheStrategy(DiskCacheStrategy.NONE)
                         .signature(new ObjectKey(new File(bgPath).lastModified()))
                         .centerCrop().into(myBgImageView);
                } else {
                    myBgImageView.setImageURI(android.net.Uri.fromFile(new File(bgPath)));
                }
            } else if (remoteBg != null && remoteBg.startsWith("http")) {
                Glide.with(this).load(remoteBg).dontAnimate().centerCrop().into(myBgImageView);
            } else {
                myBgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));
            }
        }
    }

    private void requestLoadMyStats(boolean showSpinner) {
        if (showSpinner && listSpinner != null) listSpinner.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(loadMyStatsRunnable);
        uiHandler.postDelayed(loadMyStatsRunnable, 200);
    }

    private void handleMediaLoading(MainActivity activity, String base64Data, boolean useLocalFile, String uid) {
        if (!isAdded() || avatarView == null) return;

        String customAvatarPath = useLocalFile ? activity.prefs.getString("custom_avatar_path_" + uid, null) : null;
        
        String newAvatarKey;
        if (useLocalFile && customAvatarPath != null && new File(customAvatarPath).exists()) {
            newAvatarKey = uid + "_local_" + new File(customAvatarPath).lastModified();
        } else {
            newAvatarKey = uid + "_remote_" + (base64Data != null ? String.valueOf(base64Data.hashCode()) : "empty");
        }
        
        if (newAvatarKey.equals(currentLoadedAvatar)) return;
        currentLoadedAvatar = newAvatarKey;

        if (useLocalFile) {
            if (customAvatarPath != null) {
                File localCustomFile = new File(customAvatarPath);
                if (localCustomFile.exists()) {
                    Glide.with(activity).load(localCustomFile).signature(new ObjectKey(localCustomFile.lastModified())).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
                    return;
                }
            }
            File file = new File(activity.getFilesDir(), "avatar_" + uid + ".png");
            if (file.exists()) {
                Glide.with(activity).load(file).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
                return;
            }
        }

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

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!isHidden()) {
                updateUiFromPrefs(activity);
                if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();
            }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(profileUpdateReceiver, new android.content.IntentFilter("ACTION_PROFILE_UPDATED"));
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(profileUpdateReceiver, new android.content.IntentFilter("ACTION_EDIT_PROFILE_OPENED"));
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(profileUpdateReceiver, new android.content.IntentFilter("ACTION_EDIT_PROFILE_CLOSED"));
    }

    @Override
    public void onPause() {
        super.onPause();
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(profileUpdateReceiver);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                activity.headerManager.resetHeader();
                updateUiFromPrefs(activity);
                if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();
            }
        }
    }

    private void loadLocalCacheAsync(Runnable onLoaded) {
        Utils.backgroundExecutor.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden_apps", new HashSet<>()));
            String descJson = prefs.getString("app_descriptions", "{}");
            Map<String, String> map = null;
            try { map = gson.fromJson(descJson, new TypeToken<Map<String, String>>(){}.getType()); } catch (Exception e) {}

            final Map<String, String> finalMap = map;
            new Handler(Looper.getMainLooper()).post(() -> {
                localHiddenApps.clear();
                localHiddenApps.addAll(hidden);
                if (finalMap != null) {
                    localDescriptions.clear();
                    localDescriptions.putAll(finalMap);
                }
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    public void setupOwnerAppInteractions(final MainActivity activity, final View itemView, final String pkgName) {
        final ImageView optionsBtn = itemView.findViewById(R.id.btn_app_options);
        final ImageView lockIcon = itemView.findViewById(R.id.app_lock_icon);
        final TextView descView = itemView.findViewById(R.id.app_custom_description);

        if (optionsBtn == null) return;
        optionsBtn.setVisibility(View.VISIBLE);

        boolean isHidden = localHiddenApps.contains(pkgName);
        if (lockIcon != null) lockIcon.setVisibility(isHidden ? View.VISIBLE : View.GONE);
        
        String currentDesc = localDescriptions.get(pkgName);
        if (descView != null) {
            if (currentDesc != null && !currentDesc.isEmpty()) {
                descView.setText(currentDesc);
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setVisibility(View.GONE);
            }
        }

        if (lockIcon != null) lockIcon.setOnClickListener(v -> Toast.makeText(activity, R.string.app_hidden, Toast.LENGTH_SHORT).show());

        optionsBtn.setOnClickListener(v -> {
            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_app_options, null);
            final PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setElevation(10f);

            TextView btnDesc = popupView.findViewById(R.id.menu_description);
            TextView btnHide = popupView.findViewById(R.id.menu_hide);

            btnHide.setOnClickListener(v1 -> {
                popupWindow.dismiss();
                boolean willHide = !localHiddenApps.contains(pkgName);
                if (willHide) {
                    localHiddenApps.add(pkgName);
                    if (lockIcon != null) lockIcon.setVisibility(View.VISIBLE);
                    Toast.makeText(activity, R.string.app_hidden, Toast.LENGTH_SHORT).show();
                } else {
                    localHiddenApps.remove(pkgName);
                    if (lockIcon != null) lockIcon.setVisibility(View.GONE);
                }
                prefs.edit().putStringSet("hidden_apps", localHiddenApps).apply();
                if (activity.vpsToken != null) {
                    VpsApi.setAppVisibility(activity.vpsToken, pkgName, willHide, new VpsApi.Callback() {
                        @Override public void onSuccess(String result) {}
                        @Override public void onError(String error) {}
                    });
                }
            });

            btnDesc.setOnClickListener(v12 -> {
                popupWindow.dismiss();
                uiHandler.postDelayed(() -> showDescriptionDialog(activity, pkgName, descView), 150);
            });

            popupWindow.showAsDropDown(optionsBtn, -40, 0);
        });
    }

    private void showDescriptionDialog(MainActivity activity, String pkgName, TextView descView) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_description);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;

        ImageView btnClose = dialog.findViewById(R.id.dialog_close_btn);
        Button btnSave = dialog.findViewById(R.id.dialog_save_btn);
        EditText editDesc = dialog.findViewById(R.id.dialog_edit_description);
        TextView titleView = dialog.findViewById(R.id.dialog_title);

        SharedPreferences dbNames = activity.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
        String appName = pkgName;
        String cachedName = dbNames.getString(pkgName, null);
        
        if (cachedName != null) appName = cachedName;
        else {
            PackageManager pm = activity.getPackageManager();
            try {
                int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                ApplicationInfo info;
                try { info = pm.getApplicationInfo(pkgName, 0); } 
                catch (PackageManager.NameNotFoundException e) { info = pm.getApplicationInfo(pkgName, flag); }
                appName = pm.getApplicationLabel(info).toString();
            } catch (Exception e) { 
                try {
                    String[] parts = pkgName.split("\\.");
                    String name = parts[parts.length - 1]; 
                    appName = name.substring(0, 1).toUpperCase() + name.substring(1); 
                } catch (Exception ex) { appName = pkgName; }
            }
        }

        titleView.setText(activity.getString(R.string.action_description) + " " + appName);
        String existingDesc = localDescriptions.get(pkgName);
        if (existingDesc != null) {
            editDesc.setText(existingDesc);
            editDesc.setSelection(existingDesc.length());
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String newDesc = editDesc.getText().toString().trim();
            localDescriptions.put(pkgName, newDesc);
            prefs.edit().putString("app_descriptions", gson.toJson(localDescriptions)).apply();

            if (descView != null) {
                if (!newDesc.isEmpty()) {
                    descView.setText(newDesc);
                    descView.setVisibility(View.VISIBLE);
                } else {
                    descView.setVisibility(View.GONE);
                }
            }
            requestLoadMyStats(false);
            dialog.dismiss();
            
            if (activity.vpsToken != null) {
                VpsApi.setAppDescription(activity.vpsToken, pkgName, newDesc, new VpsApi.Callback() {
                    @Override public void onSuccess(String result) {}
                    @Override public void onError(String error) {}
                });
            }
        });
        dialog.show();
    }
}
