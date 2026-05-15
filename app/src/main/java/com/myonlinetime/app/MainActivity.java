package com.myonlinetime.app;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.SmartHeaderManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import com.google.firebase.messaging.FirebaseMessaging;
import android.os.Looper;
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

    private ImageView globalImageView;
    private String currentBgPath = null;
    private ImageView previewImageView;
    private boolean isSyncingBg = false; 

    private final android.os.Handler bgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable hideBgRunnable;
    
    private BroadcastReceiver badgeReceiver;

    private final SharedPreferences.OnSharedPreferenceChangeListener notifListener = (sharedPrefs, key) -> {
        if (key != null && key.startsWith("notif_history_array_")) {
            runOnUiThread(this::updateNotificationBadge);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        VpsApi.initClient(this);
        vpsToken = appPrefs.getString("vps_access_token", null);

        int savedTheme = appPrefs.getInt("selected_theme", AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(savedTheme);

        super.onCreate(savedInstanceState);
        
        badgeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("UPDATE_BADGE_BROADCAST".equals(intent.getAction())) {
                    updateNotificationBadge();
                }
            }
        };
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(badgeReceiver, new IntentFilter("UPDATE_BADGE_BROADCAST"));

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
            if (currentTab == 0) return;
            updateNavState(0);
            navigator.switchScreen(0, null);
            syncHeaderState(); 
        });

        findViewById(R.id.nav_search).setOnClickListener(v -> { 
            if (currentTab == 1) return;
            updateNavState(1); 
            checkAuthAndLoad(1); 
        });
        
        findViewById(R.id.nav_profile).setOnClickListener(v -> { 
            if (currentTab == 4) return;
            updateNavState(4); 
            checkAuthAndLoad(4); 
        });

        findViewById(R.id.nav_usage).setOnClickListener(v -> {
            if (currentTab == 3) return;
            updateNavState(3);
            navigator.switchScreen(3, null);
            syncHeaderState(); 
        });

        findViewById(R.id.nav_settings).setOnClickListener(v -> {
            if (currentTab == 5) return;
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
        
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork("FollowerSync");

        handleNotificationIntent(getIntent());
        
        refreshGoogleAndVpsToken(true);
    }

    public void refreshGoogleAndVpsToken(boolean isStartup) {
        String refreshToken = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("vps_refresh_token", null);

        if (refreshToken != null) {
            if (isStartup) {
                loadUserAvatarToBottomNav();
                enforceLoginOverlays();
            }
            return; 
        }

        if (mGoogleSignInClient == null) return;

        mGoogleSignInClient.silentSignIn().addOnCompleteListener(this, task -> {
            try {
                final GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null && account.getIdToken() != null) {
                    
                    VpsApi.authenticateWithGoogle(MainActivity.this, account.getIdToken(), new VpsApi.LoginCallback() {
                        @Override
                        public void onSuccess(String ourServerToken) {
                            vpsToken = ourServerToken;
                            StatsHelper.syncUserProfile(MainActivity.this);

                            Utils.backgroundExecutor.execute(() -> {
                                try {
                                    SharedPreferences dbNames = getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
                                    java.util.Map<String, ?> allNames = dbNames.getAll();
                                    if (!allNames.isEmpty()) {
                                        VpsApi.syncAppNames(ourServerToken, new org.json.JSONObject(allNames));
                                    }
                                } catch (Exception ignored) {}
                            });

                            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(fcmTask -> {
                                if (fcmTask.isSuccessful() && fcmTask.getResult() != null) {
                                    VpsApi.updateFcmToken(ourServerToken, fcmTask.getResult());
                                }
                            });
                        }

                        @Override
                        public void onError(String error) { }
                    });

                    if (isStartup && navigator != null) {
                        Looper.myQueue().addIdleHandler(() -> {
                            Utils.backgroundExecutor.execute(() -> {
                                com.myonlinetime.app.utils.UsageMath.preloadCoreStats(MainActivity.this);
                            });
                            return false; 
                        });
                    }
                } else {
                    vpsToken = null;
                }
            } catch (Exception ignored) { 
                vpsToken = null;
            }
            
            loadUserAvatarToBottomNav(); 
            enforceLoginOverlays();
        });
    }

    public void clearAllFragments() {
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) {
            if (f != null) ft.remove(f);
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
        
        if (mMemoryCache != null) mMemoryCache.evictAll();
        
        if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
        if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);

        if (iconProfile != null) {
            iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
            iconProfile.setImageResource(R.drawable.ic_nav_profile);
        }

        if (prefs != null) prefs.edit().clear().apply();

        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .remove("vps_access_token")
            .remove("vps_refresh_token")
            .apply();

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

        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (current != null && current.getClass().getSimpleName().contains("NotificationsHistory")) {
            badge.setVisibility(View.GONE);
            return;
        }

        ImageView bellBtn = findViewById(R.id.header_bell_btn);
        View bellContainer = findViewById(R.id.header_bell_container);
        if ((bellBtn != null && bellBtn.getVisibility() != View.VISIBLE) || 
            (bellContainer != null && bellContainer.getVisibility() != View.VISIBLE)) {
            badge.setVisibility(View.GONE);
            return;
        }

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        String currentUid = account != null ? account.getId() : "guest";
        String cacheKey = "notif_history_array_" + currentUid;

        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        String historyJson = appPrefs.getString(cacheKey, "[]");
        
        int unreadCount = 0;
        try {
            JSONArray array = new JSONArray(historyJson);
            for (int i = 0; i < array.length(); i++) {
                if (!array.getJSONObject(i).optBoolean("isRead", false)) unreadCount++;
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (unreadCount > 0) {
            badge.setVisibility(View.VISIBLE);
            if (unreadCount > 99) badge.setText(getString(R.string.notif_max_count));
            else badge.setText(String.valueOf(unreadCount));
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void initGlobalBackground() {
        View oldVideoView = findViewById(R.id.global_background_video);
        if (oldVideoView != null) oldVideoView.setVisibility(View.GONE);

        globalImageView = findViewById(R.id.global_background_image);
        
        ViewGroup parent = (ViewGroup) globalImageView.getParent();
        int insertIndex = parent.indexOfChild(globalImageView) + 1;
        
        previewImageView = new ImageView(this);
        previewImageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImageView.setVisibility(View.INVISIBLE);
        parent.addView(previewImageView, insertIndex);
    }

    public void previewBackground(String path) {
        if (path == null) return;
        if (path.equals("none")) {
            previewBgPath = "none";
            if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            return;
        }

        previewBgPath = path;
        if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
        
        if (previewImageView != null) {
            previewImageView.setVisibility(View.VISIBLE);
            if (path.startsWith("http")) {
                Glide.with(this).load(path).centerCrop().into(previewImageView);
            } else {
                Glide.with(this).load(new File(path)).centerCrop().into(previewImageView);
            }
        }
    }
    
    public void clearPreviewBackground() {
        clearPreviewBackground(false);
    }

    public void clearPreviewBackground(boolean instant) {
        if (previewBgPath != null) {
            previewBgPath = null;
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
                if (f.getName().startsWith("my_bg_" + uid)) f.delete();
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

    private String resolveMyBackground() {
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
        if (acct == null) return null;
        String uid = acct.getId();
        
        String myBgUrl = prefs.getString("my_bg_base64", null);
        if (myBgUrl != null && myBgUrl.startsWith("http")) syncMyBackground(myBgUrl);

        if (currentTab == 4 && currentBgBase64 != null && !currentBgBase64.isEmpty() && !currentBgBase64.equals("null")) {
            return resolveBackgroundPath(currentBgBase64);
        } else {
            String targetPath = prefs.getString("custom_bg_path_" + uid, null);
            if ((targetPath == null || !new File(targetPath).exists()) && myBgUrl != null && myBgUrl.startsWith("http")) {
                return resolveBackgroundPath(myBgUrl);
            }
            return (targetPath != null && new File(targetPath).exists()) ? targetPath : null;
        }
    }

    public void updateGlobalBackground(boolean show) {
        if (!show) {
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
            return;
        }

        if (previewBgPath != null) {
            if ("none".equals(previewBgPath)) {
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
                if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
            } else {
                previewBackground(previewBgPath);
            }
            return;
        }
        
        String targetPath = resolveMyBackground();

        if (targetPath == null || targetPath.isEmpty()) {
            currentBgPath = null;
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
            return;
        }

        if (targetPath.equals(currentBgPath) && globalImageView.getVisibility() == View.VISIBLE) {
            if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
            return;
        }

        currentBgPath = targetPath;
        if (previewImageView != null) previewImageView.setVisibility(View.INVISIBLE);
        
        if (globalImageView != null) {
            globalImageView.setVisibility(View.VISIBLE);
            Glide.with(this).load(targetPath).centerCrop().into(globalImageView);
        }
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
                boolean isGif = bgUrl.toLowerCase().endsWith(".gif");
                String ext = isGif ? ".gif" : ".jpg"; 
                
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
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(notifListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUserAvatarToBottomNav(); 
        updateNotificationBadge(); 
        
        boolean hideGlobalBg = false;
        if (navigator != null && navigator.hasSubScreen()) {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current != null) {
                String fragName = current.getClass().getSimpleName();
                if (fragName.contains("OtherProfile") || fragName.contains("Notifications") || fragName.contains("Follows")) {
                    hideGlobalBg = true;
                }
            }
        }

        if (previewBgPath != null && navigator != null && navigator.hasSubScreen()) {
            previewBackground(previewBgPath);
        } else if (hideGlobalBg) {
            updateGlobalBackground(false); 
        } else {
            updateGlobalBackground(true);
        }
        
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(notifListener);
        
        if (hasPermission()) {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
            if (account != null) StatsHelper.syncUserProfile(this);
        }
        
        enforceLoginOverlays();
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            adjustHeaderForWindowMode(isInMultiWindowMode());
        }

        refreshGoogleAndVpsToken(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (badgeReceiver != null) {
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(badgeReceiver);
            } catch (Exception ignored) {}
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
            
            if ("time".equals(tab) || "notifications".equals(tab) || "other_profile".equals(tab)) {
                if (navigator != null && navigator.hasSubScreen()) {
                    navigator.closeSubScreen();
                }
                
                if ("other_profile".equals(tab)) {
                    String targetUid = intent.getStringExtra("target_uid");
                    String targetNickname = intent.getStringExtra("target_nickname");
                    if (targetUid != null && navigator != null) {
                        // === ОПЕРЕЖАЮЩАЯ ЗАГРУЗКА ПРИ ХОЛОДНОМ СТАРТЕ ===
                        if (vpsToken != null) {
                            com.myonlinetime.app.ui.OtherProfileFragment.prefetchProfile(vpsToken, targetUid);
                        }
                        // ===============================================

                        navigator.openSubScreen(com.myonlinetime.app.ui.OtherProfileFragment.newInstance(
                                targetUid, 
                                getString(R.string.title_notifications), 
                                targetNickname, 
                                "", 
                                ""
                        ));
                    }
                } else {
                    updateNavState(3); 
                    navigator.switchScreen(3, null);
                    syncHeaderState(); 
                    
                    if ("notifications".equals(tab) && navigator != null) {
                        navigator.openSubScreen(new com.myonlinetime.app.ui.NotificationsHistoryFragment());
                    }
                }
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
                    Glide.with(this).load(localFile).signature(new ObjectKey(localFile.lastModified())).circleCrop().into(iconProfile);
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
        boolean hasPerm = hasPermission();

        View headerBellContainer = findViewById(R.id.header_bell_container);
        if (headerBellContainer != null) {
            if (!hasPerm) {
                headerBellContainer.setVisibility(View.GONE);
            } else {
                headerBellContainer.setVisibility(View.VISIBLE);
            }
        }

        if (permissionOverlay != null) {
            if (!hasPerm) {
                permissionOverlay.setVisibility(View.VISIBLE);
                permissionOverlay.bringToFront();
                mainHeader.bringToFront();
            } else {
                permissionOverlay.setVisibility(View.GONE);
            }
        }

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
                        if (overlay != null) overlay.setVisibility(View.GONE);
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
