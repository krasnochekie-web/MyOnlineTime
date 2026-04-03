package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import java.util.concurrent.Executors;

public class ProfileFragment extends Fragment {

    private Set<String> localHiddenApps = new HashSet<>();
    private Map<String, String> localDescriptions = new HashMap<>();
    private SharedPreferences prefs;
    private final Gson gson = new Gson();

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

        String targetUid = getArguments() != null ? getArguments().getString("TARGET_UID") : "";

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return view;

        final String myUid = account.getId();
        final boolean isMe = targetUid.equals(myUid);
        final String finalTargetUid = targetUid;

        prefs = activity.getSharedPreferences("MyOnlineTime_Cache_" + myUid, Context.MODE_PRIVATE);

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

        TextView tabTopApps = view.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);

        final ImageView btnExpand = view.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = view.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = view.findViewById(R.id.profile_apps_container);

        btnExpand.setOnClickListener(v -> {
            for (int i = 0; i < appsContainerLocal.getChildCount(); i++) {
                appsContainerLocal.getChildAt(i).setVisibility(View.VISIBLE);
            }
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
        });

        btnCollapse.setOnClickListener(v -> {
            for (int i = 2; i < appsContainerLocal.getChildCount(); i++) {
                appsContainerLocal.getChildAt(i).setVisibility(View.GONE);
            }
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, true)));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, false)));

        if (!isMe) btnBack.setVisibility(View.VISIBLE);

        loadLocalCacheAsync(() -> {
            if (isMe && isAdded()) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isAdded()) return;
                    StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
                }, 150); 
            }
        });

        if (isMe) {
            nameView.setText(activity.prefs.getString("my_nickname", account.getDisplayName()));
            aboutView.setText(activity.prefs.getString("my_about", ""));

            Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + myUid);
            if (cachedAvatar != null) {
                Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarView);
            } else {
                File file = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                Glide.with(activity)
                     .load(file)
                     .circleCrop()
                     .error(R.drawable.bg_edit_circle) 
                     .into(avatarView);
            }

            btnEdit.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);

            btnEdit.setOnClickListener(v -> activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                    nameView.getText().toString(),
                    aboutView.getText().toString()
            )));

        } else {
            nameView.setText(activity.getString(R.string.loading));
            btnEdit.setVisibility(View.GONE);
            btnFollow.setVisibility(View.INVISIBLE);
        }

        final Runnable fetchProfileData = new Runnable() {
            @Override
            public void run() {
                VpsApi.getUser(activity, activity.vpsToken, finalTargetUid, new VpsApi.UserCallback() {
                    @Override
                    public void onLoaded(User user) {
                        if (!isAdded()) return;
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
                                    // Оставляем локальную
                                } else {
                                    if (user.photo.startsWith("http")) {
                                        Glide.with(activity).load(user.photo).circleCrop().into(avatarView);
                                    } else {
                                        Executors.newSingleThreadExecutor().execute(() -> {
                                            try {
                                                byte[] imageByteArray = android.util.Base64.decode(user.photo, android.util.Base64.DEFAULT);
                                                new Handler(Looper.getMainLooper()).post(() -> {
                                                    if (isAdded()) Glide.with(activity).asBitmap().load(imageByteArray).circleCrop().into(avatarView);
                                                });
                                            } catch (Exception e) {}
                                        });
                                    }
                                }
                            }

                            if (isMe) {
                                boolean cacheChanged = false;
                                if (user.hiddenApps != null) {
                                    localHiddenApps.clear();
                                    localHiddenApps.addAll(user.hiddenApps);
                                    prefs.edit().putStringSet("hidden_apps", localHiddenApps).apply();
                                    cacheChanged = true;
                                }
                                if (user.appDescriptions != null) {
                                    localDescriptions.clear();
                                    localDescriptions.putAll(user.appDescriptions);
                                    prefs.edit().putString("app_descriptions", gson.toJson(localDescriptions)).apply();
                                    cacheChanged = true;
                                }
                                if (cacheChanged) {
                                    StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
                                }
                            }

                            if (!isMe) {
                                if (user.hiddenApps != null) {
                                    localHiddenApps.clear();
                                    localHiddenApps.addAll(user.hiddenApps);
                                }
                                if (user.appDescriptions != null) {
                                    localDescriptions.clear();
                                    localDescriptions.putAll(user.appDescriptions);
                                }
                                renderOtherUserStats(user.topApps, appsContainerLocal, activity, weekTimeText);
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
            VpsApi.authenticateWithGoogle(activity, account.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(final String token) {
                    activity.vpsToken = token;
                    fetchProfileData.run();
                }
                @Override public void onError(String error) {}
            });
        }

        return view;
    }

    // ========================================================
    // ИСПРАВЛЕННЫЙ МЕТОД onResume
    // ========================================================
    @Override
    public void onResume() {
        super.onResume();
        // ДОБАВЛЕНО: Проверка !isHidden()
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(true);
        }
    }
    // ========================================================

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                activity.resetHeader();
                activity.updateGlobalBackground(true);
            }
        }
    }

    private void loadLocalCacheAsync(Runnable onLoaded) {
        Executors.newSingleThreadExecutor().execute(() -> {
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

    private void renderOtherUserStats(Map<String, Long> topApps, LinearLayout container, MainActivity activity, TextView weekTimeText) {
        container.removeAllViews();
        if (topApps == null || topApps.isEmpty() || activity == null) return;

        PackageManager pm = activity.getPackageManager();
        int limit = 0;
        long totalVisibleTime = 0;

        for (Map.Entry<String, Long> entry : topApps.entrySet()) {
            String pkgName = entry.getKey();

            if (localHiddenApps.contains(pkgName)) continue;
            if (limit >= 10) break;

            View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, container, false);
            if (limit >= 2) view.setVisibility(View.GONE);

            ImageView iconView = view.findViewById(R.id.app_icon);
            TextView nameView = view.findViewById(R.id.app_name);
            TextView timeView = view.findViewById(R.id.app_time);
            TextView descView = view.findViewById(R.id.app_custom_description);
            ImageView lockView = view.findViewById(R.id.app_lock_icon);
            ImageView optionsBtn = view.findViewById(R.id.btn_app_options);

            if (optionsBtn != null) optionsBtn.setVisibility(View.GONE);
            if (lockView != null) lockView.setVisibility(View.GONE);

            String description = localDescriptions.get(pkgName);
            if (description != null && !description.isEmpty() && descView != null) {
                descView.setText(description);
                descView.setVisibility(View.VISIBLE);
            }

            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
                nameView.setText(pm.getApplicationLabel(appInfo));
                iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
            } catch (Exception e) {
                nameView.setText(pkgName);
            }

            timeView.setText(Utils.formatTime(activity, entry.getValue()));
            totalVisibleTime += entry.getValue();
            
            container.addView(view);
            limit++;
        }

        if (limit == 0 && !topApps.isEmpty()) {
            String hiddenText = activity.getString(R.string.hidden_time_placeholder) + "  "; 
            SpannableString ss = new SpannableString(hiddenText);
            
            android.graphics.drawable.Drawable d = activity.getResources().getDrawable(R.drawable.ic_hidden_exclamation);
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            ImageSpan span = new ImageSpan(d, ImageSpan.ALIGN_BOTTOM);
            ss.setSpan(span, hiddenText.length() - 1, hiddenText.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

            weekTimeText.setText(ss);
            weekTimeText.setOnClickListener(v -> Toast.makeText(activity, R.string.user_hid_time, Toast.LENGTH_SHORT).show());
        } else {
            long minutes = totalVisibleTime / 1000 / 60;
            long hours = minutes / 60;
            long mins = minutes % 60;
            if (hours > 0) {
                weekTimeText.setText(activity.getString(R.string.format_hours_mins, hours, mins));
            } else {
                weekTimeText.setText(activity.getString(R.string.format_mins, mins));
            }
            weekTimeText.setOnClickListener(null); 
        }

        View btnExpand = ((View)container.getParent()).findViewById(R.id.btn_expand_apps);
        if (btnExpand != null) {
            btnExpand.setVisibility(limit > 2 ? View.VISIBLE : View.GONE);
        }
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

        PackageManager pm = activity.getPackageManager();
        String appName = pkgName;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString();
        } catch (Exception e) {}

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
