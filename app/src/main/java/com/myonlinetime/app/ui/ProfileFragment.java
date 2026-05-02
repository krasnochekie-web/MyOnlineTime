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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProfileFragment extends Fragment {

    private Set<String> localHiddenApps = new HashSet<>();
    private Map<String, String> localDescriptions = new HashMap<>();
    private SharedPreferences prefs;
    private final Gson gson = new Gson();
    
    private boolean isMe = false;
    private ImageView avatarView;
    private String currentTargetUid = "";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable loadMyStatsRunnable;
    private Runnable fetchProfileDataRunnable;

    private final android.content.BroadcastReceiver profileUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && isAdded() && isMe) {
                TextView nameView = getView().findViewById(R.id.profile_name);
                TextView aboutView = getView().findViewById(R.id.profile_about);
                
                GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                if (acct != null && nameView != null && aboutView != null) {
                    nameView.setText(activity.prefs.getString("my_nickname", "..."));
                    aboutView.setText(activity.prefs.getString("my_about", ""));
                    
                    LinearLayout appsContainerLocal = getView().findViewById(R.id.profile_apps_container);
                    ImageView btnExpand = getView().findViewById(R.id.btn_expand_apps);
                    ImageView btnCollapse = getView().findViewById(R.id.btn_collapse_apps);
                    StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
                }

                if (acct != null) {
                    String b64 = activity.prefs.getString("my_photo_base64", null);
                    handleMediaLoading(activity, b64, true, acct.getId());
                }
            }
        }
    };

    private static class AppUiData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        String description;
        boolean isDeleted;
    }

    public static ProfileFragment newInstance(String targetUid, String backTitle) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        args.putString("BACK_TITLE", backTitle);
        fragment.setArguments(args);
        return fragment;
    }
    
    public static ProfileFragment newInstance(String targetUid) {
        return newInstance(targetUid, "");
    }

    public ProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_profile, container, false);
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return view;

        String targetUid = getArguments() != null ? getArguments().getString("TARGET_UID", "") : "";
        String backTitle = getArguments() != null ? getArguments().getString("BACK_TITLE", "") : "";
        if (backTitle.isEmpty()) backTitle = activity.getString(R.string.title_search);

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return view;

        final String myUid = account.getId();
        isMe = targetUid.equals(myUid) || targetUid.isEmpty(); 
        currentTargetUid = isMe ? myUid : targetUid;

        activity.mainHeader.setVisibility(View.VISIBLE);
        if (!isMe) {
            activity.headerManager.showBackButton(backTitle, v -> activity.onBackPressed());
        } else {
            activity.headerManager.resetHeader();
        }

        prefs = activity.getSharedPreferences("MyOnlineTime_Cache_" + myUid, Context.MODE_PRIVATE);

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

        loadMyStatsRunnable = () -> {
            if (isAdded() && appsContainerLocal != null && weekTimeText != null) {
                StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
            }
        };

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

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(currentTargetUid, "followers")));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsListFragment.newInstance(currentTargetUid, "following")));

        loadLocalCacheAsync(() -> {
            if (isMe && isAdded()) requestLoadMyStats();
        });

        if (isMe) {
            String savedName = activity.prefs.getString("my_nickname", "...");
            nameView.setText(savedName);
            String myAbout = activity.prefs.getString("my_about", "");
            aboutView.setText(myAbout);
            StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

            handleMediaLoading(activity, activity.prefs.getString("my_photo_base64", null), true, myUid);

            btnEdit.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);

            btnEdit.setOnClickListener(v -> activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                    nameView.getText().toString().equals("...") ? "" : nameView.getText().toString(),
                    aboutView.getText().toString()
            )));

        } else {
            nameView.setText(activity.getString(R.string.loading));
            btnEdit.setVisibility(View.GONE);
            btnFollow.setVisibility(View.INVISIBLE);
        }

        fetchProfileDataRunnable = () -> {
            VpsApi.getUser(activity, activity.vpsToken, currentTargetUid, new VpsApi.UserCallback() {
                @Override
                public void onLoaded(User user) {
                    if (!isAdded()) return;
                    if (user != null) {
                        nameView.setText(user.nickname != null ? user.nickname : (isMe ? "..." : activity.getString(R.string.no_name)));
                        aboutView.setText(user.about != null ? user.about : "");
                        StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

                        if (isMe && user.nickname != null) activity.prefs.edit().putString("my_nickname", user.nickname).apply();
                        if (isMe && user.about != null) activity.prefs.edit().putString("my_about", user.about).apply();

                        if (user.photo != null && user.photo.length() > 10) {
                            if (isMe) {
                                activity.prefs.edit().putString("my_photo_base64", user.photo).apply();
                                activity.updateAvatarInUI();
                            }
                            handleMediaLoading(activity, user.photo, false, currentTargetUid);
                        }

                        if (user.background != null && user.background.length() > 10) {
                            if (isMe) {
                                activity.prefs.edit().putString("my_bg_base64", user.background).apply();
                                activity.syncMyBackground(user.background);
                            } else {
                                boolean isVideo = user.background.endsWith(".mp4") || user.background.endsWith(".mov");
                                activity.previewBackground(user.background, isVideo);
                            }
                        } else if (!isMe) {
                            activity.clearPreviewBackground();
                            activity.updateGlobalBackground(false);
                        }

                        if (isMe && user.createdAt != null) {
                            try {
                                java.text.SimpleDateFormat serverFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                                serverFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                                java.text.SimpleDateFormat appFormat = new java.text.SimpleDateFormat("dd MMMM yyyy", new java.util.Locale("ru"));
                                java.util.Date date = serverFormat.parse(user.createdAt);
                                activity.prefs.edit().putString("my_created_at", appFormat.format(date)).apply();
                            } catch (Exception e) {}
                        }

                        if (isMe) {
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
                            if (cacheChanged) requestLoadMyStats();
                        } else {
                            if (user.hiddenApps != null) {
                                localHiddenApps.clear();
                                localHiddenApps.addAll(user.hiddenApps);
                            }
                            if (user.appDescriptions != null) {
                                localDescriptions.clear();
                                localDescriptions.putAll(user.appDescriptions);
                            }
                            renderOtherUserStats(user.topApps, user.totalTime, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);
                        }
                    } else if (!isMe) nameView.setText(activity.getString(R.string.new_user));
                }
                @Override public void onError(String e) {}
            });

            refreshCounts(activity);

            if (!isMe) {
                VpsApi.checkIsFollowing(activity.vpsToken, currentTargetUid, new VpsApi.BooleanCallback() {
                     @Override public void onResult(final boolean isFollowing) {
                         if (!isAdded()) return;
                         updateFollowButton(btnFollow, isFollowing);
                         btnFollow.setVisibility(View.VISIBLE);
                         btnFollow.setOnClickListener(v -> {
                             boolean currentStatus = !isFollowing;
                             updateFollowButton(btnFollow, currentStatus);
                             VpsApi.setFollow(activity.vpsToken, currentTargetUid, currentStatus, new VpsApi.Callback() {
                                 @Override public void onSuccess(String s) {
                                     refreshCounts(activity);
                                 }
                                 @Override public void onError(String err) {
                                     if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();
                                 }
                             });
                         });
                     }
                });
            }
        };

        if (activity.vpsToken != null) {
            fetchProfileDataRunnable.run();
        } else {
            if (activity.mGoogleSignInClient != null) {
                activity.mGoogleSignInClient.silentSignIn().addOnSuccessListener(freshAccount -> {
                    VpsApi.authenticateWithGoogle(activity, freshAccount.getIdToken(), new VpsApi.LoginCallback() {
                        @Override
                        public void onSuccess(final String token) {
                            activity.vpsToken = token;
                            fetchProfileDataRunnable.run();
                        }
                        @Override public void onError(String error) {}
                    });
                }).addOnFailureListener(e -> {
                    VpsApi.authenticateWithGoogle(activity, account.getIdToken(), new VpsApi.LoginCallback() {
                        @Override
                        public void onSuccess(final String token) {
                            activity.vpsToken = token;
                            fetchProfileDataRunnable.run();
                        }
                        @Override public void onError(String error) {}
                    });
                });
            }
        }

        return view;
    }

    private void refreshCounts(MainActivity activity) {
        if (activity.vpsToken == null) return;
        VpsApi.getCounts(activity.vpsToken, currentTargetUid, new VpsApi.Callback() {
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

    private void requestLoadMyStats() {
        uiHandler.removeCallbacks(loadMyStatsRunnable);
        uiHandler.postDelayed(loadMyStatsRunnable, 150);
    }

    private void handleMediaLoading(MainActivity activity, String base64Data, boolean useLocalFile, String uid) {
        if (!isAdded() || avatarView == null) return;

        if (useLocalFile && isMe) {
            String customAvatarPath = activity.prefs.getString("custom_avatar_path_" + uid, null);
            if (customAvatarPath != null) {
                File localCustomFile = new File(customAvatarPath);
                if (localCustomFile.exists()) {
                    Glide.with(activity)
                         .load(localCustomFile)
                         .signature(new ObjectKey(localCustomFile.lastModified()))
                         .circleCrop()
                         .error(R.drawable.bg_edit_circle)
                         .into(avatarView);
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

    private String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!isHidden()) {
                if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();
                refreshCounts(activity);
                if (isMe) activity.updateGlobalBackground(true);
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
                
                String backTitle = getArguments() != null ? getArguments().getString("BACK_TITLE", activity.getString(R.string.title_search)) : activity.getString(R.string.title_search);
                
                if (!isMe) {
                    activity.headerManager.showBackButton(backTitle, v -> activity.onBackPressed());
                } else {
                    activity.headerManager.resetHeader();
                    
                    TextView nameView = getView() != null ? getView().findViewById(R.id.profile_name) : null;
                    TextView aboutView = getView() != null ? getView().findViewById(R.id.profile_about) : null;
                    ImageView btnExpand = getView() != null ? getView().findViewById(R.id.btn_expand_apps) : null;
                    ImageView btnCollapse = getView() != null ? getView().findViewById(R.id.btn_collapse_apps) : null;
                    GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                    
                    if (nameView != null && aboutView != null && acct != null) {
                        String currentName = activity.prefs.getString("my_nickname", "...");
                        nameView.setText(currentName);
                        aboutView.setText(activity.prefs.getString("my_about", ""));
                        
                        if (currentName.equals("...") && fetchProfileDataRunnable != null) {
                            fetchProfileDataRunnable.run();
                        }

                        LinearLayout appsContainerLocal = getView().findViewById(R.id.profile_apps_container);
                        StatsHelper.applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
                        
                        String b64 = activity.prefs.getString("my_photo_base64", null);
                        handleMediaLoading(activity, b64, true, acct.getId());
                    }
                }
                activity.updateGlobalBackground(isMe);
                
                if (fetchProfileDataRunnable != null) fetchProfileDataRunnable.run();
                refreshCounts(activity);
                
            } else {
                if (!isMe) {
                    activity.clearPreviewBackground();
                    activity.updateGlobalBackground(false);
                } else if (activity.navigator != null && activity.navigator.getCurrentTabIndex() != 4) {
                    activity.updateGlobalBackground(false);
                }
            }
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !isMe) {
            activity.clearPreviewBackground();
            activity.updateGlobalBackground(false);
        }
    }

    private void loadLocalCacheAsync(Runnable onLoaded) {
        Utils.backgroundExecutor.execute(() -> {
            Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden_apps", new HashSet<>()));
            String descJson = prefs.getString("app_descriptions", "{}");
            Map<String, String> map = null;
            try {
                map = gson.fromJson(descJson, new TypeToken<Map<String, String>>(){}.getType());
            } catch (Exception e) { e.printStackTrace(); }

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

    private void renderOtherUserStats(Map<String, Long> topApps, long serverTotalTime, LinearLayout container, MainActivity activity, TextView weekTimeText, TextView aboutView, ImageView btnExpand, ImageView btnCollapse) {
        if (container != null) {
            container.setLayoutTransition(null);
            container.removeAllViews();
        }
        if (activity == null) return;

        if (topApps == null || topApps.isEmpty()) {
            long minutes = serverTotalTime / 1000 / 60;
            long hours = minutes / 60;
            long mins = minutes % 60;
            if (weekTimeText != null) {
                weekTimeText.setText(hours > 0 ? activity.getString(R.string.format_hours_mins, hours, mins) : activity.getString(R.string.format_mins, mins));
            }
            return;
        }

        final long[] totalVisibleTime = {0};
        final List<AppUiData> preloadedData = new ArrayList<>();

        Utils.backgroundExecutor.execute(() -> {
            PackageManager pm = activity.getPackageManager();
            SharedPreferences dbNames = activity.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
            File dbIconsDir = new File(activity.getFilesDir(), "saved_app_icons");

            int limit = 0;

            for (Map.Entry<String, Long> entry : topApps.entrySet()) {
                String pkgName = entry.getKey().replaceAll("\\s+", "");

                if (localHiddenApps.contains(pkgName)) continue;
                if (limit >= 10) break;

                AppUiData data = new AppUiData();
                data.pkgName = pkgName;
                data.time = entry.getValue();
                data.description = localDescriptions.get(pkgName);
                data.isDeleted = false;

                ApplicationInfo appInfo = null;

                try {
                    appInfo = pm.getApplicationInfo(pkgName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    try {
                        int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                                   PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                        appInfo = pm.getApplicationInfo(pkgName, flag);
                        
                        boolean isInstalled = (appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                        boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        
                        if (!isInstalled && !isSystemApp) {
                            data.isDeleted = true; 
                        } else {
                            data.isDeleted = false; 
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                        data.isDeleted = true; 
                    }
                }

                String cachedName = dbNames.getString(pkgName, null);
                if (cachedName != null) {
                    data.appName = cachedName;
                } else if (appInfo != null) {
                    data.appName = pm.getApplicationLabel(appInfo).toString();
                } else {
                    data.appName = formatDeletedAppName(pkgName);
                }

                File diskIcon = new File(dbIconsDir, pkgName + ".png");
                if (diskIcon.exists()) {
                    data.icon = android.graphics.drawable.Drawable.createFromPath(diskIcon.getAbsolutePath());
                } else if (appInfo != null) {
                    try {
                        data.icon = pm.getApplicationIcon(appInfo);
                    } catch (Exception ignored) {}
                }

                preloadedData.add(data);
                totalVisibleTime[0] += entry.getValue();
                limit++;
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;

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
                        iconView.setImageResource(android.R.drawable.sym_def_app_icon);
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

        if (lockIcon != null) {
            lockIcon.setOnClickListener(v -> Toast.makeText(activity, R.string.app_hidden, Toast.LENGTH_SHORT).show());
        }

        optionsBtn.setOnClickListener(v -> {
            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_app_options, null);
            final PopupWindow popupWindow = new PopupWindow(popupView, 
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            
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
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;

        ImageView btnClose = dialog.findViewById(R.id.dialog_close_btn);
        Button btnSave = dialog.findViewById(R.id.dialog_save_btn);
        EditText editDesc = dialog.findViewById(R.id.dialog_edit_description);
        TextView titleView = dialog.findViewById(R.id.dialog_title);

        SharedPreferences dbNames = activity.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
        String appName = pkgName;
        String cachedName = dbNames.getString(pkgName, null);
        
        if (cachedName != null) {
            appName = cachedName;
        } else {
            PackageManager pm = activity.getPackageManager();
            try {
                int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                           PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                ApplicationInfo info;
                try {
                    info = pm.getApplicationInfo(pkgName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    info = pm.getApplicationInfo(pkgName, flag);
                }
                appName = pm.getApplicationLabel(info).toString();
            } catch (Exception e) {
                appName = formatDeletedAppName(pkgName);
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
