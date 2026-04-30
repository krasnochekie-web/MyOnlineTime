package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
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

// ExoPlayer импорты
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileOutputStream;
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

    // Переменные для видеоаватара
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private ImageView avatarView;

    private static class AppUiData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        String description;
        boolean isDeleted;
    }

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

        String targetUid = getArguments() != null ? getArguments().getString("TARGET_UID") : "";

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return view;

        final String myUid = account.getId();
        isMe = targetUid.equals(myUid) || targetUid.isEmpty(); // Если пусто - это свой профиль
        final String finalTargetUid = isMe ? myUid : targetUid;

        // НАСТРОЙКА НАВИГАЦИИ И ШАПКИ
        activity.mainHeader.setVisibility(View.VISIBLE);
        if (!isMe) {
            // Если чужой профиль - показываем стрелку и меняем заголовок
            activity.headerManager.showBackButton(activity.getString(R.string.title_search), v -> {
                // Закрываем этот фрагмент и возвращаемся в поиск
                if (getFragmentManager() != null) getFragmentManager().popBackStack();
            });
        } else {
            activity.headerManager.resetHeader();
        }

        prefs = activity.getSharedPreferences("MyOnlineTime_Cache_" + myUid, Context.MODE_PRIVATE);

        final TextView nameView = view.findViewById(R.id.profile_name);
        final TextView aboutView = view.findViewById(R.id.profile_about);
        
        avatarView = view.findViewById(R.id.profile_avatar);
        playerView = view.findViewById(R.id.profile_video_avatar); // Новый элемент для видео
        
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

        if (appsContainerLocal != null) {
            appsContainerLocal.setLayoutTransition(null); 
            appsContainerLocal.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    appsContainerLocal.post(() -> applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse));
                }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
        }

        btnExpand.setOnClickListener(v -> {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
            applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        btnCollapse.setOnClickListener(v -> {
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
            applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, true)));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, false)));

        loadLocalCacheAsync(() -> {
            if (isMe && isAdded()) {
                // ИСПРАВЛЕНИЕ: Очищаем контейнер перед загрузкой, чтобы избежать дублей
                if(appsContainerLocal != null) appsContainerLocal.removeAllViews();
                StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainerLocal);
            }
        });

        if (isMe) {
            nameView.setText(activity.prefs.getString("my_nickname", account.getDisplayName()));
            String myAbout = activity.prefs.getString("my_about", "");
            aboutView.setText(myAbout);
            applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

            // Обработка своей аватарки
            handleMediaLoading(activity, activity.prefs.getString("my_photo_base64", null), true, myUid);

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
                            applyCollapseLogic(aboutView, appsContainerLocal, btnExpand, btnCollapse);

                            // Обработка медиа (Фото/Видео)
                            if (user.photo != null && user.photo.length() > 10) {
                                if (isMe) activity.prefs.edit().putString("my_photo_base64", user.photo).apply();
                                handleMediaLoading(activity, user.photo, false, finalTargetUid);
                            }

                            // ОБНОВЛЕНИЕ ФОНА (Глобальное состояние)
                            if (user.background != null && user.background.length() > 10) {
                                activity.currentBgBase64 = user.background;
                                activity.updateGlobalBackground(true);
                            }

                            // === СОХРАНЕНИЕ ДАТЫ РЕГИСТРАЦИИ В КЭШ ===
                            if (isMe && user.createdAt != null) {
                                try {
                                    java.text.SimpleDateFormat serverFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                                    serverFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                                    java.text.SimpleDateFormat appFormat = new java.text.SimpleDateFormat("dd MMMM yyyy", new java.util.Locale("ru"));
                                    
                                    java.util.Date date = serverFormat.parse(user.createdAt);
                                    String prettyDate = appFormat.format(date);
                                    
                                    activity.prefs.edit().putString("my_created_at", prettyDate).apply();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            // ===========================================

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
                                    if(appsContainerLocal != null) appsContainerLocal.removeAllViews();
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
                                // ИСПРАВЛЕНИЕ: Очистка внутри метода renderOtherUserStats
                                renderOtherUserStats(user.topApps, appsContainerLocal, activity, weekTimeText, aboutView, btnExpand, btnCollapse);
                            }

                        } else if (!isMe) nameView.setText(activity.getString(R.string.new_user));
                    }
                    @Override public void onError(String e) {}
                });

                VpsApi.getCounts(activity.vpsToken, finalTargetUid, new VpsApi.Callback() {
                    @Override public void onSuccess(String result) {
                        if (!isAdded()) return; 
                        // Парсим JSON ответ от обновленного сервера
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(result);
                            followersCount.setText(String.valueOf(json.optInt("followers", 0)));
                            followingCount.setText(String.valueOf(json.optInt("following", 0)));
                        } catch (Exception e) {}
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

    // =========================================================================
    // ЛОГИКА ОТОБРАЖЕНИЯ МЕДИА (EXOPLAYER + GLIDE)
    // =========================================================================
    private void handleMediaLoading(MainActivity activity, String base64Data, boolean useLocalFile, String uid) {
        if (base64Data == null || base64Data.isEmpty()) {
            if (useLocalFile) {
                File file = new File(activity.getFilesDir(), "avatar_" + uid + ".png");
                Glide.with(activity).load(file).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
            }
            return;
        }

        if (base64Data.startsWith("http")) {
            Glide.with(activity).load(base64Data).circleCrop().into(avatarView);
            return;
        }

        Utils.backgroundExecutor.execute(() -> {
            try {
                byte[] mediaBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                
                // Проверяем "магические байты" для определения формата (MP4)
                boolean isVideo = mediaBytes.length > 8 &&
                                  mediaBytes[4] == 0x66 && mediaBytes[5] == 0x74 &&
                                  mediaBytes[6] == 0x79 && mediaBytes[7] == 0x70; // 'ftyp'

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;

                    if (isVideo && playerView != null) {
                        // Это ВИДЕО! Скрываем картинку, показываем плеер
                        avatarView.setVisibility(View.GONE);
                        playerView.setVisibility(View.VISIBLE);

                        if (exoPlayer == null) {
                            exoPlayer = new ExoPlayer.Builder(activity).build();
                            playerView.setPlayer(exoPlayer);
                            playerView.setUseController(false); // Убираем кнопки паузы
                            exoPlayer.setVolume(0f); // Без звука
                            exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE); // Бесконечный цикл
                        }

                        // Сохраняем во временный файл (ExoPlayer лучше читает с диска)
                        try {
                            File tempVid = new File(activity.getCacheDir(), "temp_avatar.mp4");
                            FileOutputStream fos = new FileOutputStream(tempVid);
                            fos.write(mediaBytes);
                            fos.close();
                            
                            MediaItem mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(tempVid));
                            exoPlayer.setMediaItem(mediaItem);
                            exoPlayer.prepare();
                            exoPlayer.play();
                        } catch (Exception e) { e.printStackTrace(); }

                    } else {
                        // Это КАРТИНКА или GIF! Скрываем плеер, показываем картинку
                        if (playerView != null) playerView.setVisibility(View.GONE);
                        avatarView.setVisibility(View.VISIBLE);
                        
                        // Glide сам поймет, GIF это или JPG
                        Glide.with(activity).load(mediaBytes).circleCrop().into(avatarView);
                    }
                });
            } catch (Exception e) {}
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Обязательно освобождаем память плеера при уходе с экрана!
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    // =========================================================================
    // ОСТАЛЬНОЙ КОД (ПАРСЕР, ЛОГИКА РАСКРЫТИЯ, ЛОКАЛЬНЫЙ КЭШ) ОСТАЛСЯ БЕЗ ИЗМЕНЕНИЙ
    // =========================================================================

    private String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) {
            return pkg;
        }
    }

    private void applyCollapseLogic(TextView aboutView, LinearLayout container, ImageView btnExpand, ImageView btnCollapse) {
        if (container == null || aboutView == null || btnExpand == null || btnCollapse == null) return;
        
        boolean isEmptyDesc = aboutView.getText().toString().trim().isEmpty();
        aboutView.setVisibility(isEmptyDesc ? View.GONE : View.VISIBLE);
        
        if (aboutView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) aboutView.getParent();
            if (parent.getBackground() != null || parent.getChildCount() == 1) {
                parent.setVisibility(isEmptyDesc ? View.GONE : View.VISIBLE);
            }
        }
        
        int limit = isEmptyDesc ? 3 : 2;
        int count = container.getChildCount();
        
        if (count <= limit) {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.GONE);
            for (int i = 0; i < count; i++) {
                container.getChildAt(i).setVisibility(View.VISIBLE);
            }
        } else {
            if (btnCollapse.getVisibility() == View.VISIBLE) {
                btnExpand.setVisibility(View.GONE);
                for (int i = 0; i < count; i++) {
                    container.getChildAt(i).setVisibility(View.VISIBLE);
                }
            } else {
                btnExpand.setVisibility(View.VISIBLE);
                for (int i = 0; i < count; i++) {
                    container.getChildAt(i).setVisibility(i < limit ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(isMe);
        }
        // Воспроизводим видео, если вернулись
        if (exoPlayer != null) exoPlayer.play();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Ставим на паузу видео, когда уходим
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                activity.mainHeader.setVisibility(View.VISIBLE);
                // Восстанавливаем стрелочку назад, если это чужой профиль
                if (!isMe) {
                    activity.headerManager.showBackButton(activity.getString(R.string.title_search), v -> {
                        if (getFragmentManager() != null) getFragmentManager().popBackStack();
                    });
                } else {
                    activity.headerManager.resetHeader();
                }
                activity.updateGlobalBackground(isMe);
                if (exoPlayer != null) exoPlayer.play();
            } else {
                if (activity.navigator != null && activity.navigator.getCurrentTabIndex() != 4) {
                    activity.updateGlobalBackground(false);
                }
                if (exoPlayer != null) exoPlayer.pause();
            }
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

    private void renderOtherUserStats(Map<String, Long> topApps, LinearLayout container, MainActivity activity, TextView weekTimeText, TextView aboutView, ImageView btnExpand, ImageView btnCollapse) {
        // ИСПРАВЛЕНИЕ ДУБЛИРОВАНИЯ
        container.removeAllViews();
        if (topApps == null || topApps.isEmpty() || activity == null) return;

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

                int collapsedLimit = aboutView.getText().toString().trim().isEmpty() ? 3 : 2;
                int currentLimit = 0;
                
                for (AppUiData data : preloadedData) {
                    View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, container, false);
                    
                    if (btnCollapse.getVisibility() != View.VISIBLE && currentLimit >= collapsedLimit) {
                        view.setVisibility(View.GONE);
                    }

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
                    
                    container.addView(view);
                    currentLimit++;
                }

                if (currentLimit == 0 && !topApps.isEmpty()) {
                    String hiddenText = activity.getString(R.string.hidden_time_placeholder) + "  "; 
                    SpannableString ss = new SpannableString(hiddenText);
                    
                    android.graphics.drawable.Drawable d = activity.getResources().getDrawable(R.drawable.ic_hidden_exclamation);
                    d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
                    ImageSpan span = new ImageSpan(d, ImageSpan.ALIGN_BOTTOM);
                    ss.setSpan(span, hiddenText.length() - 1, hiddenText.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

                    weekTimeText.setText(ss);
                    weekTimeText.setOnClickListener(v -> Toast.makeText(activity, R.string.user_hid_time, Toast.LENGTH_SHORT).show());
                } else {
                    long minutes = totalVisibleTime[0] / 1000 / 60;
                    long hours = minutes / 60;
                    long mins = minutes % 60;
                    if (hours > 0) {
                        weekTimeText.setText(activity.getString(R.string.format_hours_mins, hours, mins));
                    } else {
                        weekTimeText.setText(activity.getString(R.string.format_mins, mins));
                    }
                    weekTimeText.setOnClickListener(null); 
                }

                applyCollapseLogic(aboutView, container, btnExpand, btnCollapse);
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
