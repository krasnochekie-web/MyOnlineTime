package com.myonlinetime.app;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.SmartHeaderManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;

import java.io.File;
import org.json.JSONArray;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.myonlinetime.app.utils.Utils;

public class MainActivity extends AppCompatActivity {

    public FrameLayout container;
    public View mainHeader;
    private View bottomNav;
    public View mainRoot;
    public TextView headerTitle;
    public String currentBgBase64 = null; 
    public ImageView headerBackBtn;
    
    public String previewBgPath = null;
    public boolean isPreviewVideo = false;
    
    private ImageView iconFeed, iconSearch, iconUsage, iconProfile, iconSettings;
    
    private int currentTab = 0;

    public GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 9001;
    
    public SharedPreferences prefs;
    public LruCache<String, Bitmap> mMemoryCache;
    
    public String vpsToken = null;
    public com.myonlinetime.app.utils.AppNavigator navigator;
    public SmartHeaderManager headerManager;

    private View permissionOverlay;

    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private ImageView globalImageView;
    private String currentBgPath = null;
    
    // Сохранение секунд видео для возврата без сброса
    private java.util.HashMap<String, Long> mediaPositions = new java.util.HashMap<>();
    
    private final android.os.Handler bgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable hideBgRunnable;
    private boolean isSyncingBg = false; 

    private final SharedPreferences.OnSharedPreferenceChangeListener notifListener = (sharedPrefs, key) -> {
        if ("notif_history_array".equals(key)) {
            runOnUiThread(this::updateNotificationBadge);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        int savedTheme = appPrefs.getInt("selected_theme", AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(savedTheme);

        super.onCreate(savedInstanceState);
        
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, Bundle savedInstanceState) {
                super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                String fragName = f.getClass().getSimpleName();
                
                if (fragName.contains("NotificationsHistory") || fragName.contains("EditProfile") || fragName.contains("Follows")) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        v.setTranslationZ(100f); 
                    }
                }
                
                container.post(() -> enforceLoginOverlays());
            }
        }, false);

        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);

        setContentView(R.layout.activity_main);
        
        navigator = new com.myonlinetime.app.utils.AppNavigator(this, R.id.fragment_container);
        mainRoot = findViewById(R.id.main_root);
        
        prefs = getSharedPreferences("UserProfile", MODE_PRIVATE);
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount() / 1024; }
        };

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("603306715003-0ptgu4fqnldcsoon9niprvi772m2ebks.apps.googleusercontent.com") 
                .requestEmail().build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
        container = (FrameLayout) findViewById(R.id.fragment_container);

        mainHeader = findViewById(R.id.app_header);
        final float cornerRadius = 24f * getResources().getDisplayMetrics().density;
        mainHeader.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), (int) (view.getHeight() + cornerRadius), cornerRadius);
            }
        });

        headerTitle = (TextView) findViewById(R.id.header_title);
        headerBackBtn = (ImageView) findViewById(R.id.header_back_btn);
        bottomNav = (View) findViewById(R.id.bottom_nav_container);
        
        headerManager = new SmartHeaderManager(this, this::updateNotificationBadge);

        ImageView headerBellBtn = findViewById(R.id.header_bell_btn);
        View headerBellContainer = findViewById(R.id.header_bell_container);
        
        View.OnClickListener bellListener = v -> {
            if (navigator != null) {
                navigator.openSubScreen(new com.myonlinetime.app.ui.NotificationsHistoryFragment());
            }
        };
        
        if (headerBellBtn != null) headerBellBtn.setOnClickListener(bellListener);
        if (headerBellContainer != null) headerBellContainer.setOnClickListener(bellListener);
        
        updateNotificationBadge();

        iconFeed = (ImageView) findViewById(R.id.icon_feed); 
        iconSearch = (ImageView) findViewById(R.id.icon_search);
        iconProfile = (ImageView) findViewById(R.id.icon_profile); 
        iconUsage = (ImageView) findViewById(R.id.icon_usage); 
        iconSettings = (ImageView) findViewById(R.id.icon_settings); 

        permissionOverlay = findViewById(R.id.permission_overlay);
        if (permissionOverlay != null) {
            Button btnGrant = permissionOverlay.findViewById(R.id.btn_grant_permission);
            if (btnGrant != null) {
                btnGrant.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            }
        }

        findViewById(R.id.nav_feed).setOnClickListener(v -> {
            updateNavState(0);
            navigator.switchScreen(0, null);
            syncHeaderState(); 
        });

        findViewById(R.id.nav_search).setOnClickListener(v -> { 
            updateNavState(1); 
            checkAuthAndLoad(1); 
        });
        
        findViewById(R.id.nav_profile).setOnClickListener(v -> { 
            updateNavState(4); 
            checkAuthAndLoad(4); 
        });

        findViewById(R.id.nav_usage).setOnClickListener(v -> {
            updateNavState(3);
            navigator.switchScreen(3, null);
            syncHeaderState(); 
        });

        findViewById(R.id.nav_settings).setOnClickListener(v -> {
            updateNavState(5);
            navigator.switchScreen(5, null);
            syncHeaderState(); 
        });
        
        headerBackBtn.setOnClickListener(v -> handleBackNavigation());

        initGlobalBackground();
        updateGlobalBackground(true);

        int tabToOpen = 0; 
        if (appPrefs.contains("open_tab_after_login")) {
            tabToOpen = appPrefs.getInt("open_tab_after_login", 0);
            appPrefs.edit().remove("open_tab_after_login").apply();
        } else if (savedInstanceState != null) {
            tabToOpen = savedInstanceState.getInt("SAVED_TAB", 0);
        }

        updateNavState(tabToOpen);
        if (tabToOpen == 4) {
            GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
            navigator.switchScreen(4, acct != null ? acct.getId() : "");
        } else {
            navigator.switchScreen(tabToOpen, null);
        }
        syncHeaderState();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        androidx.work.PeriodicWorkRequest weeklyWorkRequest = new androidx.work.PeriodicWorkRequest.Builder(
                com.myonlinetime.app.utils.WeeklyStatsWorker.class, 7, java.util.concurrent.TimeUnit.DAYS)
                .build();
        
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "WeeklyStatsNotification",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                weeklyWorkRequest
        );

        handleNotificationIntent(getIntent());
        
        mGoogleSignInClient.silentSignIn().addOnCompleteListener(this, task -> {
            StatsHelper.syncUserProfile(MainActivity.this);
            loadUserAvatarToBottomNav(); 
            enforceLoginOverlays();
            
            try {
                final GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null && navigator != null) {
                    Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
                        @Override
                        public boolean queueIdle() {
                            Utils.backgroundExecutor.execute(() -> {
                                com.myonlinetime.app.utils.UsageMath.preloadCoreStats(MainActivity.this);
                            });
                            return false; 
                        }
                    });
                }
            } catch (Exception ignored) { }
        });
    } 

    public void clearAllFragments() {
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) {
            if (f != null) {
                ft.remove(f);
            }
        }
        ft.commitAllowingStateLoss();
        fm.executePendingTransactions();
        
        navigator = new com.myonlinetime.app.utils.AppNavigator(this, R.id.fragment_container);
    }

    public void performSignOut() {
        if (mGoogleSignInClient != null) {
            mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                resetAccountState();
                clearAllFragments(); 
                
                updateNavState(5);
                navigator.switchScreen(5, null);
                loadUserAvatarToBottomNav();
                enforceLoginOverlays();
                Toast.makeText(this, getString(R.string.settings_sign_out), Toast.LENGTH_SHORT).show();
            });
        }
    }

    public void resetAccountState() {
        vpsToken = null;
        currentBgBase64 = null;
        currentBgPath = null;
        previewBgPath = null;
        mediaPositions.clear();
        
        if (mMemoryCache != null) mMemoryCache.evictAll();
        
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        if (globalImageView != null) {
            globalImageView.setImageDrawable(null);
            globalImageView.setVisibility(View.INVISIBLE);
        }
        if (playerView != null) {
            playerView.setVisibility(View.INVISIBLE);
        }

        if (iconProfile != null) {
            iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
            iconProfile.setImageResource(R.drawable.ic_nav_profile);
        }

        if (prefs != null) {
            prefs.edit().clear().apply();
        }

        try {
            File dir = getFilesDir();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith("my_bg_") || name.startsWith("avatar_") || name.startsWith("custom_")) {
                        f.delete();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void syncHeaderState() {
        getSupportFragmentManager().executePendingTransactions();
        if (navigator != null && navigator.hasSubScreen()) {
            headerManager.updateHeaderAfterBack();
        } else if (headerManager != null) {
            headerManager.resetHeader();
            container.post(this::enforceLoginOverlays);
        }
    }

    public void updateNotificationBadge() {
        TextView badge = findViewById(R.id.header_bell_badge);
        if (badge == null) return;

        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        String historyJson = appPrefs.getString("notif_history_array", "[]");
        
        int unreadCount = 0;
        try {
            JSONArray array = new JSONArray(historyJson);
            for (int i = 0; i < array.length(); i++) {
                if (!array.getJSONObject(i).optBoolean("isRead", false)) {
                    unreadCount++;
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (unreadCount > 0) {
            badge.setVisibility(View.VISIBLE);
            if (unreadCount > 99) {
                badge.setText(getString(R.string.notif_max_count));
            } else {
                badge.setText(String.valueOf(unreadCount));
            }
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void initGlobalBackground() {
        playerView = findViewById(R.id.global_background_video);
        globalImageView = findViewById(R.id.global_background_image);
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
        exoPlayer.setVolume(0f);
        exoPlayer.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    private void switchMedia(String newPath, boolean isVideo) {
        if (newPath == null) return;

        // Если медиа то же самое - снимаем с паузы МГНОВЕННО, без загрузок!
        if (newPath.equals(currentBgPath)) {
            if (isVideo) {
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
                if (playerView != null) playerView.setVisibility(View.VISIBLE);
                if (exoPlayer != null && !exoPlayer.isPlaying()) exoPlayer.play();
            } else {
                if (playerView != null) playerView.setVisibility(View.INVISIBLE);
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                if (globalImageView != null) globalImageView.setVisibility(View.VISIBLE);
            }
            return;
        }

        if (currentBgPath != null && exoPlayer != null) {
            mediaPositions.put(currentBgPath, exoPlayer.getCurrentPosition());
        }

        currentBgPath = newPath;

        if (isVideo) {
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            if (playerView != null) playerView.setVisibility(View.VISIBLE);
            
            MediaItem mediaItem = newPath.startsWith("http") ? 
                    MediaItem.fromUri(Uri.parse(newPath)) : 
                    MediaItem.fromUri(Uri.fromFile(new File(newPath)));
                    
            if (exoPlayer != null) {
                exoPlayer.setMediaItem(mediaItem);
                exoPlayer.prepare();
                Long savedPos = mediaPositions.get(newPath);
                if (savedPos != null) {
                    exoPlayer.seekTo(savedPos);
                }
                exoPlayer.play();
            }
        } else {
            if (playerView != null) playerView.setVisibility(View.INVISIBLE);
            if (exoPlayer != null) exoPlayer.pause();
            
            if (globalImageView != null) {
                globalImageView.setVisibility(View.VISIBLE);
                // .dontAnimate() - убивает мерцания и вспышки фото
                if (newPath.startsWith("http")) {
                    Glide.with(MainActivity.this).load(newPath).dontAnimate().centerCrop().into(globalImageView);
                } else {
                    Glide.with(MainActivity.this).load(new File(newPath)).dontAnimate().centerCrop().into(globalImageView);
                }
            }
        }
    }

    public void previewBackground(String path, boolean isVideo) {
        if (path == null) return;

        if (path.equals("none")) {
            previewBgPath = "none";
            bgHandler.removeCallbacks(hideBgRunnable);
            if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
            if (playerView != null) playerView.setVisibility(View.INVISIBLE);
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            return;
        }

        previewBgPath = path;
        isPreviewVideo = isVideo;
        
        bgHandler.removeCallbacks(hideBgRunnable);
        switchMedia(path, isVideo);
    }

    // === ИСПРАВЛЕНИЕ: Мы больше не заменяем чужой фон на свой, если переходим на Поиск/Настройки ===
    public void clearPreviewBackground() {
        if (previewBgPath != null) {
            previewBgPath = null;
            // Если мы переходим на свой профиль - грузим свой фон.
            // Иначе - просто ставим текущий фон (чужой) на паузу!
            if (currentTab == 4) {
                updateGlobalBackground(true);
            } else {
                updateGlobalBackground(false);
            }
        }
    }

    public void deleteMyBackgroundLocal() {
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
        if (acct == null) return;
        String uid = acct.getId();

        File dir = getFilesDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("my_bg_" + uid)) {
                    f.delete();
                }
            }
        }

        prefs.edit()
            .remove("custom_bg_path_" + uid)
            .remove("custom_bg_is_video_" + uid)
            .remove("synced_bg_url_" + uid)
            .remove("my_bg_base64")
            .apply();

        currentBgBase64 = null;
        currentBgPath = null;
        
        updateGlobalBackground(true);

        if (vpsToken != null) {
            VpsApi.deleteBackground(vpsToken, new VpsApi.Callback() {
                @Override public void onSuccess(String result) {}
                @Override public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, getString(R.string.err_server) + " " + error, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private String resolveBackgroundPath(String path) {
        if (path == null) return null;
        if (path.startsWith("http")) {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
            if (account != null) {
                String uid = account.getId();
                String cachedUrl = prefs.getString("synced_bg_url_" + uid, "");
                if (path.equals(cachedUrl)) {
                    String localPath = prefs.getString("custom_bg_path_" + uid, "");
                    File localFile = new File(localPath);
                    if (localFile.exists()) {
                        return localFile.getAbsolutePath();
                    }
                }
            }
        }
        return path;
    }

    public void updateGlobalBackground(boolean show) {
        if (hideBgRunnable == null) {
            hideBgRunnable = () -> {
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                if (playerView != null) playerView.setVisibility(View.INVISIBLE);
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            };
        }

        if (!show) {
            bgHandler.removeCallbacks(hideBgRunnable);
            bgHandler.postDelayed(hideBgRunnable, 200);
            return;
        }

        bgHandler.removeCallbacks(hideBgRunnable);

        if (previewBgPath != null) {
            if ("none".equals(previewBgPath)) {
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                if (playerView != null) playerView.setVisibility(View.INVISIBLE);
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
                return;
            }
            switchMedia(previewBgPath, isPreviewVideo);
            return;
        }

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
        if (acct == null) {
            bgHandler.postDelayed(hideBgRunnable, 200);
            return;
        }
        
        String uid = acct.getId();

        String myBgUrl = prefs.getString("my_bg_base64", null);
        if (myBgUrl != null && myBgUrl.startsWith("http")) {
            syncMyBackground(myBgUrl);
        }

        String targetPath = null;
        boolean isVideo = false;

        if (currentTab == 4 && currentBgBase64 != null && !currentBgBase64.isEmpty() && !currentBgBase64.equals("null")) {
            targetPath = resolveBackgroundPath(currentBgBase64);
            isVideo = targetPath != null && (targetPath.toLowerCase().endsWith(".mp4") || targetPath.toLowerCase().endsWith(".mov"));
        } else {
            targetPath = prefs.getString("custom_bg_path_" + uid, null);
            isVideo = prefs.getBoolean("custom_bg_is_video_" + uid, false);
            
            if ((targetPath == null || !new File(targetPath).exists()) && myBgUrl != null && myBgUrl.startsWith("http")) {
                targetPath = resolveBackgroundPath(myBgUrl);
                isVideo = targetPath != null && (targetPath.toLowerCase().endsWith(".mp4") || targetPath.toLowerCase().endsWith(".mov"));
            }
        }

        if (targetPath == null || targetPath.isEmpty() || (!new File(targetPath).exists() && !targetPath.startsWith("http"))) {
            bgHandler.postDelayed(hideBgRunnable, 200);
            currentBgPath = null;
            return;
        }

        if (currentBgPath != null && currentBgPath.startsWith("http") && targetPath != null && !targetPath.startsWith("http")) {
            String syncedUrl = prefs.getString("synced_bg_url_" + uid, "");
            if (currentBgPath.equals(syncedUrl) || currentBgPath.equals(currentBgBase64)) {
                return; 
            }
        }

        switchMedia(targetPath, isVideo);
    }   
    
    public void syncMyBackground(String bgUrl) {
        if (bgUrl == null || bgUrl.isEmpty() || bgUrl.equals("null") || isSyncingBg) return;
        
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) return;
        
        String uid = account.getId();
        String cachedUrl = prefs.getString("synced_bg_url_" + uid, "");
        String currentCustom = prefs.getString("custom_bg_path_" + uid, "");
        
        if (bgUrl.equals(cachedUrl) && new File(currentCustom).exists()) {
            return; 
        }

        isSyncingBg = true;
        Utils.backgroundExecutor.execute(() -> {
            try {
                boolean isVideo = bgUrl.toLowerCase().endsWith(".mp4") || bgUrl.toLowerCase().endsWith(".mov");
                boolean isGif = bgUrl.toLowerCase().endsWith(".gif");
                String ext = isVideo ? ".mp4" : (isGif ? ".gif" : ".jpg");
                
                File dir = getFilesDir();
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith("my_bg_" + uid)) f.delete();
                    }
                }

                File localFile = new File(getFilesDir(), "my_bg_" + uid + "_" + System.currentTimeMillis() + ext);

                java.net.URL url = new java.net.URL(bgUrl);
                java.io.InputStream is = url.openStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();

                runOnUiThread(() -> {
                    isSyncingBg = false;
                    prefs.edit()
                         .putString("synced_bg_url_" + uid, bgUrl)
                         .putString("custom_bg_path_" + uid, localFile.getAbsolutePath())
                         .putBoolean("custom_bg_is_video_" + uid, isVideo)
                         .apply();
                         
                    updateGlobalBackground(true);
                });
            } catch (Exception e) {
                e.printStackTrace();
                isSyncingBg = false;
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(notifListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUserAvatarToBottomNav(); 
        updateNotificationBadge(); 
        
        if (previewBgPath != null) {
            previewBackground(previewBgPath, isPreviewVideo);
        } else {
            updateGlobalBackground(true);
        }
        
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(notifListener);
        
        if (permissionOverlay != null) {
            if (hasPermission()) {
                permissionOverlay.setVisibility(View.GONE);
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
                if (account != null) StatsHelper.syncUserProfile(this);
            } else {
                permissionOverlay.setVisibility(View.VISIBLE);
                permissionOverlay.bringToFront();
                mainHeader.bringToFront();
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            adjustHeaderForWindowMode(isInMultiWindowMode());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); 
        handleNotificationIntent(intent);
    }
    
    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.hasExtra("open_tab")) {
            String tab = intent.getStringExtra("open_tab");
            if ("time".equals(tab)) {
                if (navigator != null && navigator.hasSubScreen()) {
                    navigator.closeSubScreen();
                }
                updateNavState(3); 
                navigator.switchScreen(3, null);
                syncHeaderState(); 
                intent.removeExtra("open_tab");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("SAVED_TAB", currentTab); 
    }

    public void updateAvatarInUI() {
        runOnUiThread(this::loadUserAvatarToBottomNav);
    }

    private void loadUserAvatarToBottomNav() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            if (iconProfile != null) {
                iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
                iconProfile.setImageResource(R.drawable.ic_nav_profile);
            }
            return;
        }

        if (iconProfile != null) {
            String uid = account.getId();
            
            String customAvatarPath = prefs.getString("custom_avatar_path_" + uid, null);
            if (customAvatarPath != null) {
                File localFile = new File(customAvatarPath);
                if (localFile.exists()) {
                    iconProfile.setImageTintList(null); 
                    Glide.with(this)
                         .load(localFile)
                         .signature(new ObjectKey(localFile.lastModified())) 
                         .circleCrop()
                         .into(iconProfile);
                    return;
                }
            }

            Bitmap cachedAvatar = mMemoryCache.get("avatar_" + uid);
            if (cachedAvatar != null) {
                iconProfile.setImageTintList(null); 
                Glide.with(this).load(cachedAvatar).circleCrop().into(iconProfile);
                return;
            } 
            
            String savedUrl = prefs.getString("my_photo_base64", null);
            if (savedUrl != null) {
                iconProfile.setImageTintList(null);
                if (savedUrl.startsWith("http")) {
                    Glide.with(this).load(savedUrl).circleCrop().into(iconProfile);
                } else {
                    try {
                        byte[] bytes = android.util.Base64.decode(savedUrl, android.util.Base64.DEFAULT);
                        Glide.with(this).load(bytes).circleCrop().into(iconProfile);
                    } catch (Exception e) {}
                }
            } else {
                iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
                iconProfile.setImageResource(R.drawable.ic_nav_profile); 
            }
        }
    }
    
    private void showBottomNav() {
        if (bottomNav == null) return;
        bottomNav.animate().translationY(0).setDuration(250).start();
    }

    private void hideBottomNav() {
        if (bottomNav == null) return;
        bottomNav.animate().translationY(bottomNav.getHeight() + 50).setDuration(250).start();
    }

    @Override
    public void onBackPressed() { handleBackNavigation(); }  

    private void handleBackNavigation() {
        if (navigator.closeSubScreen()) {
            syncHeaderState(); 
            return; 
        }
        if (currentTab != 0) {
            updateNavState(0);
            navigator.switchScreen(0, null);
            syncHeaderState(); 
        } else {
            super.onBackPressed();
        }
    }

    private void checkAuthAndLoad(int tabIndex) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        
        if (tabIndex == 1) {
            navigator.switchScreen(1, null); 
        } else if (tabIndex == 4) {
            if (account != null) StatsHelper.syncUserProfile(MainActivity.this);
            navigator.switchScreen(4, account != null ? account.getId() : ""); 
        }
        
        getSupportFragmentManager().executePendingTransactions();
        syncHeaderState(); 
        
        enforceLoginOverlays();
        container.post(this::enforceLoginOverlays);
    } 

    public void enforceLoginOverlays() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        boolean noAuth = (account == null);

        View oldGlobalOverlay = container.findViewWithTag("login_screen_overlay");
        if (oldGlobalOverlay != null && oldGlobalOverlay.getParent() == container) {
            container.removeView(oldGlobalOverlay);
        }

        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f != null && f.getView() instanceof ViewGroup) {
                String fragName = f.getClass().getSimpleName();
                
                if (fragName.contains("Notification") || fragName.contains("Edit") || fragName.contains("Follow")) {
                    continue;
                }

                boolean needsAuth = fragName.equals("ProfileFragment") || fragName.equals("SearchFragment");

                if (needsAuth) {
                    ViewGroup root = (ViewGroup) f.getView();
                    View overlay = root.findViewWithTag("login_screen_overlay");

                    if (noAuth) {
                        if (overlay == null) {
                            try {
                                overlay = getLayoutInflater().inflate(R.layout.layout_login_required, root, false);
                                overlay.setClickable(true); 
                                overlay.setTag("login_screen_overlay");
                                
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                    overlay.setTranslationZ(50f); 
                                }
                                
                                Button btn = overlay.findViewById(R.id.btn_login_center);
                                if (btn != null) btn.setOnClickListener(v -> startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN));
                                
                                root.addView(overlay);
                            } catch (Exception ignored) { }
                        } else {
                            overlay.setVisibility(View.VISIBLE);
                            overlay.bringToFront();
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                overlay.setTranslationZ(50f); 
                            }
                        }
                    } else {
                        if (overlay != null) {
                            overlay.setVisibility(View.GONE);
                        }
                    }
                }
            }
        }
    }

    public void showLoginScreen() { enforceLoginOverlays(); }
    public void hideLoginScreen() { enforceLoginOverlays(); }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                final GoogleSignInAccount acct = task.getResult(ApiException.class);
                
                resetAccountState();
                clearAllFragments(); 
                
                VpsApi.authenticateWithGoogle(MainActivity.this, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String ourServerToken) {
                        vpsToken = ourServerToken;
                        StatsHelper.syncUserProfile(MainActivity.this);

                        runOnUiThread(() -> {
                            updateNavState(4);
                            navigator.switchScreen(4, acct.getId());
                            loadUserAvatarToBottomNav();
                            enforceLoginOverlays();
                            
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(MainActivity.this)
                                .sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                        });
                    }
                    @Override
                    public void onError(String error) {
                        Toast.makeText(MainActivity.this, getString(R.string.err_server) + error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (ApiException e) {
                Toast.makeText(this, getString(R.string.err_login_failed), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateNavState(int index) {
        currentTab = index;
        
        // Переход по вкладкам. Мягко отключаем превью (без зачистки пути плеера).
        clearPreviewBackground();
        
        mainHeader.setVisibility(View.VISIBLE);
        mainHeader.bringToFront(); 
        if (permissionOverlay != null && permissionOverlay.getVisibility() == View.VISIBLE) {
            mainHeader.bringToFront();
        }
        bottomNav.setVisibility(View.VISIBLE);
        showBottomNav();

        iconFeed.setSelected(index == 0);
        iconSearch.setSelected(index == 1);
        iconProfile.setSelected(index == 4); 
        iconUsage.setSelected(index == 3);
        iconSettings.setSelected(index == 5); 
        
        container.post(this::enforceLoginOverlays);
    }

    private boolean hasPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, android.content.res.Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        adjustHeaderForWindowMode(isInMultiWindowMode);
    }

    private void adjustHeaderForWindowMode(boolean isMultiWindow) {
        if (mainHeader == null || !(mainHeader instanceof ViewGroup)) return;
        
        ViewGroup headerGroup = (ViewGroup) mainHeader;
        if (headerGroup.getChildCount() == 0) return;
        
        View innerHeader = headerGroup.getChildAt(0);
        ViewGroup.LayoutParams innerParams = innerHeader.getLayoutParams();

        if (isMultiWindow) {
            innerParams.height = (int) (56 * getResources().getDisplayMetrics().density);
            innerHeader.setPadding(
                innerHeader.getPaddingLeft(), 
                0, 
                innerHeader.getPaddingRight(), 
                innerHeader.getPaddingBottom()
            );
            mainHeader.setPadding(0, 0, 0, 0);
        } else {
            innerParams.height = (int) (76 * getResources().getDisplayMetrics().density);
            innerHeader.setPadding(
                innerHeader.getPaddingLeft(), 
                (int) (20 * getResources().getDisplayMetrics().density), 
                innerHeader.getPaddingRight(), 
                innerHeader.getPaddingBottom()
            );
            mainHeader.requestApplyInsets();
        }
        
        innerHeader.setLayoutParams(innerParams);
    }
}
