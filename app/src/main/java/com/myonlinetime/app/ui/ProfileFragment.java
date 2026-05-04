package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
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

    // === ЖЕСТКАЯ ПАМЯТЬ ДЛЯ ИЗОЛЯЦИИ ОБНОВЛЕНИЙ ===
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
                updateUiFromPrefs(activity);
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
        final View originalView = inflater.inflate(R.layout.layout_profile, container, false);
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return originalView;

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return originalView;
        myUid = account.getId();

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerManager.resetHeader();

        // Убеждаемся, что старый глобальный фон отключен, чтобы не было дублей и затемнений
        activity.updateGlobalBackground(false);

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null ? originalView.getLayoutParams() : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        myBgImageView = new ImageView(activity);
        myBgImageView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        myBgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        myBgImageView.setBackgroundColor(Color.parseColor("#121212")); 

        wrapper.addView(myBgImageView);
        wrapper.addView(originalView);

        prefs = activity.getSharedPreferences("MyOnlineTime_Cache_" + myUid, Context.MODE_PRIVATE);

        avatarView = originalView.findViewById(R.id.profile_avatar);
        final View btnEdit = originalView.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = originalView.findViewById(R.id.btn_follow);
        final TextView weekTimeText = originalView.findViewById(R.id.profile_week_time);
        View followersClick = originalView.findViewById(R.id.container_followers);
        View followingClick = originalView.findViewById(R.id.container_following);
        TextView tabTopApps = originalView.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);
        final ImageView btnExpand = originalView.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = originalView.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = originalView.findViewById(R.id.profile_apps_container);

        btnFollow.setVisibility(View.GONE);
        btnEdit.setVisibility(View.VISIBLE);

        loadMyStatsRunnable = () -> {
            if (isAdded() && appsContainerLocal != null && weekTimeText != null) {
                StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
            }
        };

        btnExpand.setOnClickListener(v -> {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(wrapper.findViewById(R.id.profile_about), appsContainerLocal, btnExpand, btnCollapse);
        });

        btnCollapse.setOnClickListener(v -> {
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
            StatsHelper.applyCollapseLogic(wrapper.findViewById(R.id.profile_about), appsContainerLocal, btnExpand, btnCollapse);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(myUid, "followers")));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(myUid, "following")));

        btnEdit.setOnClickListener(v -> {
            TextView nameView = wrapper.findViewById(R.id.profile_name);
            TextView aboutView = wrapper.findViewById(R.id.profile_about);
            activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                    nameView.getText().toString().equals("...") ? "" : nameView.getText().toString(),
                    aboutView.getText().toString()
            ));
        });

        fetchProfileDataRunnable = () -> {
            VpsApi.getUser(activity, activity.vpsToken, myUid, new VpsApi.UserCallback() {
                @Override
                public void onLoaded(User user) {
                    if (!isAdded()) return;

                    if (EditProfileFragment.isProfileUploading || (System.currentTimeMillis() - EditProfileFragment.lastProfileSyncTime < 5000)) {
                        return;
                    }

                    if (user != null) {
                        if (user.nickname != null) activity.prefs.edit().putString("my_nickname", user.nickname).apply();
                        if (user.about != null) activity.prefs.edit().putString("my_about", user.about).apply();

                        if (user.photo != null && user.photo.length() > 5) {
                            String currentPhoto = activity.prefs.getString("my_photo_base64", "");
                            if (!currentPhoto.startsWith(user.photo)) {
                                activity.prefs.edit().putString("my_photo_base64", user.photo).apply();
                                activity.updateAvatarInUI();
                            }
                        }

                        if (user.background != null && user.background.length() > 5) {
                            String currentBg = activity.prefs.getString("my_bg_base64", "");
                            if (!currentBg.startsWith(user.background)) {
                                activity.prefs.edit().putString("my_bg_base64", user.background).apply();
                                activity.syncMyBackground(user.background);
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
                        if (cacheChanged) requestLoadMyStats();
                    }
                }
                @Override public void onError(String e) {}
            });
        };

        loadLocalCacheAsync(() -> {
            if (isAdded()) requestLoadMyStats();
        });

        updateUiFromPrefs(activity);
        
        if (activity.vpsToken != null) fetchProfileDataRunnable.run();
        refreshCounts(activity);

        return wrapper;
    }

    private void updateUiFromPrefs(MainActivity activity) {
        if (getView() == null || !isAdded()) return;
        TextView nameView = getView().findViewById(R.id.profile_name);
        TextView aboutView = getView().findViewById(R.id.profile_about);
        LinearLayout appsContainerLocal = getView().findViewById(R.id.profile_apps_container);
        ImageView btnExpand = getView().findViewById(R.id.btn_expand_apps);
        ImageView btnCollapse = getView().findViewById(R.id.btn_collapse_apps);

        // Обновляем текст ТОЛЬКО если он реально изменился
        String newName = activity.prefs.getString("my_nickname", "...");
        if (!newName.equals(nameView.getText().toString())) nameView.setText(newName);

        String newAbout = activity.prefs.getString("my_about", "");
        if (!newAbout.equals(aboutView.getText().toString())) {
            aboutView.setText(newAbout);
            StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        }

        TextView followersCount = getView().findViewById(R.id.txt_followers_count);
        TextView followingCount = getView().findViewById(R.id.txt_following_count);
        if (followersCount != null) followersCount.setText(String.valueOf(prefs.getInt("followers_count", 0)));
        if (followingCount != null) followingCount.setText(String.valueOf(prefs.getInt("following_count", 0)));

        // Обработка аватарки (строго изолированно)
        String photoUrl = activity.prefs.getString("my_photo_base64", null);
        handleMediaLoading(activity, photoUrl, true, myUid);

        // Обработка фона (строго изолированно)
        if (myBgImageView != null) {
            String myBgUrl = activity.prefs.getString("my_bg_base64", null);
            String customBgPath = activity.prefs.getString("custom_bg_path_" + myUid, null);

            String bgHash = (myBgUrl != null) ? myBgUrl : "empty";
            String newBgKey = myUid + "_" + customBgPath + "_" + bgHash;

            // ЕСЛИ ФОН НЕ МЕНЯЛСЯ — МЫ ВООБЩЕ НЕ ТРОГАЕМ GLIDE (Нет моргания!)
            if (!newBgKey.equals(currentLoadedBg)) {
                currentLoadedBg = newBgKey;

                if (customBgPath != null && new File(customBgPath).exists()) {
                    File bgFile = new File(customBgPath);
                    Glide.with(activity)
                         .load(bgFile)
                         .signature(new ObjectKey(bgFile.lastModified()))
                         .centerCrop()
                         .into(myBgImageView);
                } else if (myBgUrl != null && !myBgUrl.isEmpty() && !myBgUrl.equals("null")) {
                    Glide.with(activity)
                         .load(myBgUrl)
                         .centerCrop()
                         .into(myBgImageView);
                         
                    activity.syncMyBackground(myBgUrl);
                } else {
                    myBgImageView.setImageDrawable(null);
                }
            }
        }
    }

    private void refreshCounts(MainActivity activity) {
        if (activity.vpsToken == null) return;
        VpsApi.getCounts(activity.vpsToken, myUid, new VpsApi.Callback() {
            @Override public void onSuccess(String result) {
                if (!isAdded() || getView() == null) return; 
                try {
                    org.json.JSONObject json = new org.json.JSONObject(result);
                    int followers = json.optInt("followers", 0);
                    int following = json.optInt("following", 0);
                    
                    prefs.edit().putInt("followers_count", followers).putInt("following_count", following).apply();

                    TextView followersCount = getView().findViewById(R.id.txt_followers_count);
                    TextView followingCount = getView().findViewById(R.id.txt_following_count);
                    if (followersCount != null) followersCount.setText(String.valueOf(followers));
                    if (followingCount != null) followingCount.setText(String.valueOf(following));
                } catch (Exception e) {}
            }
            @Override public void onError(String error) {}
        });
    }

    private void requestLoadMyStats() {
        uiHandler.removeCallbacks(loadMyStatsRunnable);
        uiHandler.postDelayed(loadMyStatsRunnable, 150);
    }

    private void handleMediaLoading(MainActivity activity, String photoUrl, boolean useLocalFile, String uid) {
        if (!isAdded() || avatarView == null) return;

        String customAvatarPath = useLocalFile ? activity.prefs.getString("custom_avatar_path_" + uid, null) : null;
        String urlHash = photoUrl != null ? String.valueOf(photoUrl.hashCode()) : "empty";
        String newAvatarKey = uid + "_" + customAvatarPath + "_" + urlHash;
        
        // ЕСЛИ АВАТАРКА НЕ МЕНЯЛАСЬ — ВЫХОДИМ (Нет моргания!)
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

        if (photoUrl == null || photoUrl.isEmpty() || photoUrl.equals("null")) {
            Glide.with(activity).load(R.drawable.bg_edit_circle).circleCrop().into(avatarView);
            return;
        }

        Glide.with(activity).load(photoUrl).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
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
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!isHidden()) {
                updateUiFromPrefs(activity);
                if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();
                refreshCounts(activity);
                activity.updateGlobalBackground(false); 
            }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(profileUpdateReceiver, new android.content.IntentFilter("ACTION_PROFILE_UPDATED"));
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
                refreshCounts(activity);
                activity.updateGlobalBackground(false); 
            }
        }
    }

    private void loadLocalCacheAsync(Runnable onLoaded) {
        Utils.backgroundExecutor.execute(() -> {
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
                showDescriptionDialog(activity, pkgName, descView);
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
            } catch (Exception e) { appName = formatDeletedAppName(pkgName); }
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
            requestLoadMyStats();
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
