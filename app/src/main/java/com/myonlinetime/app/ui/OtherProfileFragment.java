package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public class OtherProfileFragment extends Fragment {

    private static final long DEFAULT_PRELOAD_BG_BYTES = 5L * 1024L * 1024L;
    private static final float BG_BITMAP_SCREEN_SCALE = 1.0f;

    private ImageView avatarView;
    private ImageView bgImageView;
    private FrameLayout rootWrapper;
    private String targetUid = "";
    private String backTitle = "";

    private float lastTouchX = 0;
    private float lastTouchY = 0;

    private long renderGeneration = 0;
    private long fragmentCreationTime = 0;

    private ProgressBar listSpinner;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public static final android.util.LruCache<String, User> prefetchUserCache = new android.util.LruCache<>(50);
    public static final android.util.LruCache<String, String> prefetchCountsCache = new android.util.LruCache<>(50);
    public static final android.util.LruCache<String, Boolean> prefetchFollowCache = new android.util.LruCache<>(50);

    public static final android.util.LruCache<String, byte[]> prefetchBgBytesCache = new android.util.LruCache<String, byte[]>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    public static final android.util.LruCache<String, Bitmap> prefetchBgBitmapCache = new android.util.LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private static volatile int sTargetW = 0;
    private static volatile int sTargetH = 0;

    private static void ensureTargetSize(Context ctx) {
        if (sTargetW > 0 && sTargetH > 0) return;
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            sTargetW = Math.max(1, (int) (dm.widthPixels * BG_BITMAP_SCREEN_SCALE));
            sTargetH = Math.max(1, (int) (dm.heightPixels * BG_BITMAP_SCREEN_SCALE));
        } catch (Throwable ignored) {
            sTargetW = 1080;
            sTargetH = 1920;
        }
    }

    public static void preloadBackgrounds(List<User> users) {
        preloadBackgrounds(users, DEFAULT_PRELOAD_BG_BYTES);
    }

    public static void preloadBackgrounds(List<User> users, long maxBytes) {
        if (users == null || users.isEmpty()) return;
        final long limit = maxBytes > 0 ? maxBytes : DEFAULT_PRELOAD_BG_BYTES;

        Utils.backgroundExecutor.execute(() -> {
            Set<String> seenUrls = new HashSet<>();

            for (User u : users) {
                if (u == null) continue;
                final String bgUrl = u.background;
                if (bgUrl == null || !bgUrl.startsWith("http")) continue;
                if (!seenUrls.add(bgUrl)) continue;

                final boolean isGif = bgUrl.toLowerCase().endsWith(".gif");
                boolean hasBytes = prefetchBgBytesCache.get(bgUrl) != null;
                boolean hasBitmap = !isGif && prefetchBgBitmapCache.get(bgUrl) != null;
                if (hasBytes && (isGif || hasBitmap)) continue;

                byte[] bytes = hasBytes ? prefetchBgBytesCache.get(bgUrl) : null;

                if (bytes == null) {
                    bytes = downloadBytesCapped(bgUrl, limit);
                    if (bytes != null) prefetchBgBytesCache.put(bgUrl, bytes);
                }

                if (!isGif && bytes != null && prefetchBgBitmapCache.get(bgUrl) == null) {
                    Bitmap bmp = decodeDownsampledBitmap(bytes, sTargetW, sTargetH);
                    if (bmp != null) {
                        try { bmp.prepareToDraw(); } catch (Throwable ignored) {}
                        prefetchBgBitmapCache.put(bgUrl, bmp);
                    }
                }
            }
        });
    }

    private static byte[] downloadBytesCapped(String urlStr, long maxBytes) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream is = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestMethod("GET");

            int contentLength = conn.getContentLength();
            if (contentLength > 0 && contentLength > maxBytes) return null;

            is = conn.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[16384];
            long total = 0;
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, n);
                total += n;
                if (total > maxBytes) return null;
            }
            return total > 0 ? buffer.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) { try { is.close(); } catch (Exception ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private static Bitmap decodeDownsampledBitmap(byte[] bytes, int reqW, int reqH) {
        if (bytes == null || bytes.length == 0) return null;
        if (reqW <= 0) reqW = 1080;
        if (reqH <= 0) reqH = 1920;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, probe);
            int outW = probe.outWidth;
            int outH = probe.outHeight;
            if (outW <= 0 || outH <= 0) return null;

            int sampleSize = 1;
            while ((outW / (sampleSize * 2)) >= reqW && (outH / (sampleSize * 2)) >= reqH) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private String prefetchBg = "";
    private int prefetchFollowers = 0;
    private int prefetchFollowing = 0;
    private boolean prefetchIsFollowing = false;

    private String currentDisplayedBg = null;

    // Защита от гонки follow: пока true — dataLoader не трогает btnFollow/follow-cache.
    private boolean followInFlight = false;

    public static void prefetchProfile(String vpsToken, String uid) {
        if (vpsToken == null || uid == null || uid.isEmpty()) return;

        if (prefetchUserCache.get(uid) == null || prefetchCountsCache.get(uid) == null || prefetchFollowCache.get(uid) == null) {
            VpsApi.getAggregatedProfile(null, vpsToken, uid, new VpsApi.AggregatedProfileCallback() {
                @Override
                public void onLoaded(User user, int followers, int following, boolean isFollowing) {
                    if (user != null) {
                        user.followers = followers;
                        user.following = following;
                        user.isFollowing = isFollowing;
                        prefetchUserCache.put(uid, user);
                        try {
                            org.json.JSONObject countsObj = new org.json.JSONObject();
                            countsObj.put("followers", followers);
                            countsObj.put("following", following);
                            prefetchCountsCache.put(uid, countsObj.toString());
                        } catch (Exception ignored) {}
                        prefetchFollowCache.put(uid, isFollowing);

                        if (user.background != null && user.background.startsWith("http")) {
                            List<User> one = new ArrayList<>(1);
                            one.add(user);
                            preloadBackgrounds(one, DEFAULT_PRELOAD_BG_BYTES);
                        }
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

        ensureTargetSize(activity);

        // КРИТИЧНО: гасим глобальный фон MainActivity — он сейчас под нами и просвечивает.
        activity.updateGlobalBackground(false);

        targetUid = getArguments() != null ? getArguments().getString("TARGET_UID", "") : "";
        backTitle = getArguments() != null ? getArguments().getString("BACK_TITLE", activity.getString(R.string.title_search)) : activity.getString(R.string.title_search);

        String argName = getArguments() != null ? getArguments().getString("PREFETCH_NICKNAME", "") : "";
        String argAbout = getArguments() != null ? getArguments().getString("PREFETCH_ABOUT", "") : "";
        String argPhoto = getArguments() != null ? getArguments().getString("PREFETCH_PHOTO", "") : "";
        prefetchBg = getArguments() != null ? getArguments().getString("PREFETCH_BG", "") : "";
        prefetchFollowers = getArguments() != null ? getArguments().getInt("PREFETCH_FOLLOWERS", 0) : 0;
        prefetchFollowing = getArguments() != null ? getArguments().getInt("PREFETCH_FOLLOWING", 0) : 0;
        prefetchIsFollowing = getArguments() != null ? getArguments().getBoolean("PREFETCH_IS_FOLLOWING", false) : false;

        // Кэш — приоритет.
        final User cachedUser = prefetchUserCache.get(targetUid);
        if (cachedUser != null) {
            if (cachedUser.nickname != null && !cachedUser.nickname.isEmpty()) argName = cachedUser.nickname;
            if (cachedUser.about != null) argAbout = cachedUser.about;
            if (cachedUser.photo != null && !cachedUser.photo.isEmpty()) argPhoto = cachedUser.photo;
            if (cachedUser.background != null) prefetchBg = cachedUser.background;
        }
        final boolean cachedUserIsFull = cachedUser != null
                && cachedUser.about != null
                && cachedUser.background != null;

        // Приоритет кэша для follow-состояния и счётчиков.
        Boolean cachedFollow = prefetchFollowCache.get(targetUid);
        boolean effectiveIsFollowing = cachedFollow != null ? cachedFollow : prefetchIsFollowing;
        int effectiveFollowersTmp = prefetchFollowers;
        int effectiveFollowingTmp = prefetchFollowing;
        try {
            String countsJson = prefetchCountsCache.get(targetUid);
            if (countsJson != null) {
                org.json.JSONObject cobj = new org.json.JSONObject(countsJson);
                effectiveFollowersTmp = cobj.optInt("followers", effectiveFollowersTmp);
                effectiveFollowingTmp = cobj.optInt("following", effectiveFollowingTmp);
            }
        } catch (Exception ignored) {}

        // Final-снапшоты для всех лямбд ниже (Java требует effectively final).
        final int effectiveFollowers = effectiveFollowersTmp;
        final int effectiveFollowing = effectiveFollowingTmp;

        // === Контейнер ===
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null
                ? originalView.getLayoutParams()
                : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ГАРАНТИЯ непрозрачности: даже если bgImageView ещё ничего не показал —
        // wrapper уже залит цветом темы и сквозь него ничего не видно.
        wrapper.setBackgroundColor(ContextCompat.getColor(activity, R.color.bgDynamic));
        rootWrapper = wrapper;

        // === Фон ===
        bgImageView = new ImageView(activity);
        bgImageView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        boolean appliedSync = false;
        if (prefetchBg != null && !prefetchBg.isEmpty() && bgAllowedByPrefs(activity, prefetchBg)) {
            Bitmap ready = prefetchBgBitmapCache.get(prefetchBg);
            if (ready != null) {
                bgImageView.setImageBitmap(ready);
                currentDisplayedBg = prefetchBg;
                appliedSync = true;
            }
        }
        if (!appliedSync) {
            bgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));
            currentDisplayedBg = "disabled";
        }

        wrapper.addView(bgImageView);
        wrapper.addView(originalView);

        if (!appliedSync && prefetchBg != null && !prefetchBg.isEmpty()
                && bgAllowedByPrefs(activity, prefetchBg)) {
            final String urlF = prefetchBg;
            final boolean isGif = urlF.toLowerCase().endsWith(".gif");
            byte[] bytes = prefetchBgBytesCache.get(urlF);
            if (bytes != null) {
                Glide.with(activity).load(bytes).centerCrop().dontAnimate().into(bgImageView);
            } else {
                Glide.with(activity).load(urlF).centerCrop().dontAnimate().into(bgImageView);
            }
            currentDisplayedBg = urlF;
            if (!isGif) warmUpBitmapCacheAsync(activity, urlF);
        }

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

        btnFollow.setTag(effectiveIsFollowing);
        updateFollowButton(btnFollow, effectiveIsFollowing);
        btnFollow.setVisibility(View.VISIBLE);

        final TextView txtFollowersCount = originalView.findViewById(R.id.txt_followers_count);
        final TextView txtFollowingCount = originalView.findViewById(R.id.txt_following_count);
        if (txtFollowersCount != null) txtFollowersCount.setText(String.valueOf(effectiveFollowers));
        if (txtFollowingCount != null) txtFollowingCount.setText(String.valueOf(effectiveFollowing));

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
            if (btnFollow.getTag() == null) return;

            boolean currentStatus = (boolean) btnFollow.getTag();
            final boolean nextStatus = !currentStatus;

            // === ОПТИМИСТИЧНЫЙ UI ===
            btnFollow.setTag(nextStatus);
            updateFollowButton(btnFollow, nextStatus);
            prefetchFollowCache.put(targetUid, nextStatus);

            int optimisticFollowers = effectiveFollowers;
            try {
                if (txtFollowersCount != null) {
                    int count = Integer.parseInt(txtFollowersCount.getText().toString());
                    count = nextStatus ? count + 1 : count - 1;
                    if (count < 0) count = 0;
                    optimisticFollowers = count;
                    txtFollowersCount.setText(String.valueOf(count));
                }
            } catch (Exception ignored) {}

            // Синхронизируем все три кэша.
            try {
                int curFollowing = effectiveFollowing;
                String existing = prefetchCountsCache.get(targetUid);
                if (existing != null) {
                    org.json.JSONObject cobj = new org.json.JSONObject(existing);
                    curFollowing = cobj.optInt("following", curFollowing);
                }
                org.json.JSONObject countsObj = new org.json.JSONObject();
                countsObj.put("followers", optimisticFollowers);
                countsObj.put("following", curFollowing);
                prefetchCountsCache.put(targetUid, countsObj.toString());
            } catch (Exception ignored) {}

            User cachedU = prefetchUserCache.get(targetUid);
            if (cachedU != null) {
                cachedU.followers = optimisticFollowers;
                cachedU.isFollowing = nextStatus;
                prefetchUserCache.put(targetUid, cachedU);
            }

            // Помечаем in-flight, чтобы dataLoader не перетёр оптимистичное состояние ответом со старым isFollowing.
            followInFlight = true;
            final int optimisticFollowersF = optimisticFollowers;

            if (activity.vpsToken != null) {
                VpsApi.setFollow(activity.vpsToken, targetUid, nextStatus, new VpsApi.Callback() {
                    @Override public void onSuccess(String s) {
                        uiHandler.post(() -> {
                            followInFlight = false;
                            if (!isAdded()) return;

                            // Подкрутим свой following в кэше моего профиля (для ProfileFragment).
                            GoogleSignInAccount myAcc = GoogleSignIn.getLastSignedInAccount(activity);
                            if (myAcc != null) {
                                prefetchCountsCache.remove(myAcc.getId());
                                User myCached = prefetchUserCache.get(myAcc.getId());
                                if (myCached != null) {
                                    myCached.following = Math.max(0, myCached.following + (nextStatus ? 1 : -1));
                                    prefetchUserCache.put(myAcc.getId(), myCached);
                                }
                                activity.sendBroadcast(new Intent("ACTION_PROFILE_UPDATED").setPackage(activity.getPackageName()));
                            }
                        });
                    }
                    @Override public void onError(String err) {
                        uiHandler.post(() -> {
                            followInFlight = false;
                            if (!isAdded()) return;

                            // Откат: возвращаем кнопку, счётчик и все кэши обратно.
                            btnFollow.setTag(currentStatus);
                            updateFollowButton(btnFollow, currentStatus);
                            prefetchFollowCache.put(targetUid, currentStatus);

                            int rollback = optimisticFollowersF + (nextStatus ? -1 : 1);
                            if (rollback < 0) rollback = 0;
                            if (txtFollowersCount != null) {
                                txtFollowersCount.setText(String.valueOf(rollback));
                            }
                            try {
                                int curFollowing = effectiveFollowing;
                                String existing = prefetchCountsCache.get(targetUid);
                                if (existing != null) {
                                    org.json.JSONObject cobj = new org.json.JSONObject(existing);
                                    curFollowing = cobj.optInt("following", curFollowing);
                                }
                                org.json.JSONObject countsObj = new org.json.JSONObject();
                                countsObj.put("followers", rollback);
                                countsObj.put("following", curFollowing);
                                prefetchCountsCache.put(targetUid, countsObj.toString());
                            } catch (Exception ignored) {}

                            User cachedRollback = prefetchUserCache.get(targetUid);
                            if (cachedRollback != null) {
                                cachedRollback.followers = rollback;
                                cachedRollback.isFollowing = currentStatus;
                                prefetchUserCache.put(targetUid, cachedRollback);
                            }

                            Toast.makeText(activity, activity.getString(R.string.err_server) + err, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } else {
                // Нет токена — откатываем сразу.
                followInFlight = false;
                btnFollow.setTag(currentStatus);
                updateFollowButton(btnFollow, currentStatus);
                prefetchFollowCache.put(targetUid, currentStatus);
                if (txtFollowersCount != null) {
                    txtFollowersCount.setText(String.valueOf(Math.max(0, optimisticFollowersF + (nextStatus ? -1 : 1))));
                }
            }
        });

        final Runnable dataLoader = new Runnable() {
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

                        // Кнопку и follow-кэш трогаем ТОЛЬКО если юзер не в процессе клика.
                        if (!followInFlight) {
                            Boolean prevFollow = prefetchFollowCache.get(targetUid);
                            prefetchFollowCache.put(targetUid, isFollowing);
                            if (prevFollow == null || prevFollow != isFollowing) {
                                btnFollow.setTag(isFollowing);
                                updateFollowButton(btnFollow, isFollowing);
                            }

                            if (txtFollowersCount != null) {
                                String newFollowers = String.valueOf(followers);
                                if (!txtFollowersCount.getText().toString().equals(newFollowers)) {
                                    txtFollowersCount.setText(newFollowers);
                                }
                            }
                            try {
                                org.json.JSONObject countsObj = new org.json.JSONObject();
                                countsObj.put("followers", followers);
                                countsObj.put("following", following);
                                prefetchCountsCache.put(targetUid, countsObj.toString());
                            } catch (Exception ignored) {}
                        }

                        // Following — не зависит от click race, можно обновить всегда.
                        if (txtFollowingCount != null) {
                            String newFollowing = String.valueOf(following);
                            if (!txtFollowingCount.getText().toString().equals(newFollowing)) {
                                txtFollowingCount.setText(newFollowing);
                            }
                        }

                        if (user != null) {
                            if (!followInFlight) {
                                user.followers = followers;
                                user.isFollowing = isFollowing;
                            } else {
                                // Сохраняем in-flight оптимистичное состояние, но обновляем остальное.
                                User existing = prefetchUserCache.get(targetUid);
                                if (existing != null) {
                                    user.followers = existing.followers;
                                    user.isFollowing = existing.isFollowing;
                                }
                            }
                            user.following = following;
                            prefetchUserCache.put(targetUid, user);

                            if (user.nickname != null && !nameView.getText().toString().equals(user.nickname)) {
                                nameView.setText(user.nickname);
                            }

                            if (user.about != null) {
                                if (!aboutView.getText().toString().equals(user.about)) {
                                    aboutView.setText(user.about);
                                }
                                int desiredVis = user.about.trim().isEmpty() ? View.GONE : View.VISIBLE;
                                if (aboutView.getVisibility() != desiredVis) {
                                    aboutView.setVisibility(desiredVis);
                                }
                            }

                            applyCollapseSafely(aboutView, appsContainerLocal, btnExpand, btnCollapse);
                            if (user.photo != null && user.photo.length() > 5) handleMediaLoading(act, user.photo);

                            if (user.topApps == null) user.topApps = new HashMap<>();

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

        if (cachedUserIsFull) {
            uiHandler.postDelayed(dataLoader, 350);
        } else {
            uiHandler.post(dataLoader);
        }

        return wrapper;
    }

    private void warmUpBitmapCacheAsync(final Context ctx, final String bgUrl) {
        if (bgUrl == null || bgUrl.isEmpty()) return;
        if (bgUrl.toLowerCase().endsWith(".gif")) return;
        if (prefetchBgBitmapCache.get(bgUrl) != null) return;

        final Context appCtx = ctx.getApplicationContext();
        Utils.backgroundExecutor.execute(() -> {
            try {
                if (prefetchBgBitmapCache.get(bgUrl) != null) return;
                ensureTargetSize(appCtx);

                byte[] bytes = prefetchBgBytesCache.get(bgUrl);
                if (bytes == null) {
                    bytes = downloadBytesCapped(bgUrl, DEFAULT_PRELOAD_BG_BYTES);
                    if (bytes != null) prefetchBgBytesCache.put(bgUrl, bytes);
                }
                if (bytes == null) return;

                Bitmap bmp = decodeDownsampledBitmap(bytes, sTargetW, sTargetH);
                if (bmp != null) {
                    try { bmp.prepareToDraw(); } catch (Throwable ignored) {}
                    prefetchBgBitmapCache.put(bgUrl, bmp);
                }
            } catch (Throwable ignored) {}
        });
    }

    private boolean bgAllowedByPrefs(Context ctx, String bgUrl) {
        if (ctx == null || bgUrl == null || bgUrl.length() < 5) return false;
        SharedPreferences appPrefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        boolean isGlobalEnabled = appPrefs.getBoolean("bg_global_enabled", true);
        boolean isOthersEnabled = appPrefs.getBoolean("bg_others_profile_enabled", true);
        boolean isOthersImages = appPrefs.getBoolean("bg_others_images_enabled", true);
        boolean isOthersGifs = appPrefs.getBoolean("bg_others_gifs_enabled", true);

        if (!isGlobalEnabled || !isOthersEnabled) return false;
        boolean isGif = bgUrl.toLowerCase().endsWith(".gif");
        if (isGif && !isOthersGifs) return false;
        if (!isGif && !isOthersImages) return false;
        return true;
    }

    private void updateBackgroundFromPrefs(MainActivity activity, String bgUrl) {
        if (bgImageView == null || !isAdded()) return;

        if (bgUrl == null) bgUrl = "";

        if (!bgAllowedByPrefs(activity, bgUrl)) {
            if (!"disabled".equals(currentDisplayedBg)) {
                currentDisplayedBg = "disabled";
                bgImageView.setImageDrawable(new ColorDrawable(ContextCompat.getColor(activity, R.color.bgDynamic)));
            }
            return;
        }

        if (bgUrl.equals(currentDisplayedBg)) return;

        final String urlF = bgUrl;
        final boolean isGif = urlF.toLowerCase().endsWith(".gif");

        if (!isGif) {
            Bitmap ready = prefetchBgBitmapCache.get(urlF);
            if (ready != null) {
                bgImageView.setImageBitmap(ready);
                currentDisplayedBg = urlF;
                return;
            }
        }

        byte[] bytes = prefetchBgBytesCache.get(urlF);
        if (bytes != null) {
            Glide.with(activity).load(bytes).centerCrop().dontAnimate().into(bgImageView);
        } else {
            Glide.with(activity).load(urlF).centerCrop().dontAnimate().into(bgImageView);
        }
        currentDisplayedBg = urlF;

        if (!isGif) warmUpBitmapCacheAsync(activity, urlF);
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

    private void handleMediaLoading(MainActivity activity, String photoUrl) {
        if (!isAdded() || avatarView == null) return;
        if (photoUrl == null || photoUrl.isEmpty() || photoUrl.equals("null")) {
            Glide.with(activity).load(R.drawable.bg_edit_circle).circleCrop().into(avatarView);
            return;
        }
        Glide.with(activity).load(photoUrl).circleCrop().error(R.drawable.bg_edit_circle).into(avatarView);
    }

    /** Пере-применить follow-состояние и счётчики из кэшей. Вызываем при возврате на экран. */
    private void reapplyFollowStateFromCache() {
        if (!isAdded() || getView() == null) return;
        if (followInFlight) return; // ничего не трогаем, если мы в полёте

        View root = getView();
        Button btnFollow = root.findViewById(R.id.btn_follow);
        TextView txtFollowers = root.findViewById(R.id.txt_followers_count);
        TextView txtFollowing = root.findViewById(R.id.txt_following_count);

        Boolean cachedFollow = prefetchFollowCache.get(targetUid);
        if (btnFollow != null && cachedFollow != null) {
            btnFollow.setTag(cachedFollow);
            updateFollowButton(btnFollow, cachedFollow);
        }

        try {
            String countsJson = prefetchCountsCache.get(targetUid);
            if (countsJson != null) {
                org.json.JSONObject cobj = new org.json.JSONObject(countsJson);
                int f = cobj.optInt("followers", -1);
                int g = cobj.optInt("following", -1);
                if (f >= 0 && txtFollowers != null) txtFollowers.setText(String.valueOf(f));
                if (g >= 0 && txtFollowing != null) txtFollowing.setText(String.valueOf(g));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !isHidden()) {
            // ВАЖНО: гасим глобальный фон каждый раз — иначе после возврата он может вернуться.
            activity.updateGlobalBackground(false);

            User cachedUser = prefetchUserCache.get(targetUid);
            if (cachedUser != null) updateBackgroundFromPrefs(activity, cachedUser.background);

            reapplyFollowStateFromCache();
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

            // Снова гасим глобальный фон — мы опять «впереди».
            activity.updateGlobalBackground(false);

            User cachedUser = prefetchUserCache.get(targetUid);
            if (cachedUser != null) updateBackgroundFromPrefs(activity, cachedUser.background);

            reapplyFollowStateFromCache();

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
        if (topApps == null) {
            return;
        }

        final long myGen = ++renderGeneration;

        if (topApps.isEmpty()) {
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
