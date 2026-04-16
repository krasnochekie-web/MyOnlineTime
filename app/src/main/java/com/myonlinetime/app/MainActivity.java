package com.myonlinetime.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.SmartHeaderManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

import com.myonlinetime.app.models.User;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.bumptech.glide.Glide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
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
    public ImageView headerBackBtn;
    
    private ImageView iconFeed, iconSearch, iconUsage, iconProfile, iconSettings;
    
    private int currentTab = 0;

    public GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 9001;
    private static final int RC_PICK_IMAGE = 9002;
    
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
    
    private int originalHeaderPaddingTop = -1;
    private int originalHeaderHeight = -1;
    
    private final android.os.Handler bgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable hideBgRunnable;

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
        
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE 
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
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
                View loginView = container.findViewWithTag("login_screen_overlay");
                if (loginView != null) loginView.setVisibility(View.GONE);
                
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
            hideLoginScreen(); 
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
            hideLoginScreen(); 
            updateNavState(3);
            navigator.switchScreen(3, null);
            syncHeaderState(); 
        });

        findViewById(R.id.nav_settings).setOnClickListener(v -> {
            hideLoginScreen(); 
            updateNavState(5);
            navigator.switchScreen(5, null);
            syncHeaderState(); 
        });
        
        headerBackBtn.setOnClickListener(v -> handleBackNavigation());

        initGlobalBackground();
        updateGlobalBackground(true);

        int tabToOpen = 0; 
        if (savedInstanceState != null) {
            tabToOpen = savedInstanceState.getInt("SAVED_TAB", 0);
        }

        updateNavState(tabToOpen);
        navigator.switchScreen(tabToOpen, null);
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

    public void syncHeaderState() {
        getSupportFragmentManager().executePendingTransactions();
        
        if (navigator != null && navigator.hasSubScreen()) {
            headerManager.updateHeaderAfterBack();
        } else if (headerManager != null) {
            headerManager.resetHeader();
            
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
            if (account == null && (currentTab == 1 || currentTab == 4)) {
                View loginView = container.findViewWithTag("login_screen_overlay");
                if (loginView != null) {
                    loginView.setVisibility(View.VISIBLE);
                } else {
                    showLoginScreen();
                }
            }
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
    }

    public void updateGlobalBackground(boolean show) {
        String path = prefs.getString("custom_bg_path", null);
        boolean isVideo = prefs.getBoolean("custom_bg_is_video", false);

        if (hideBgRunnable == null) {
            hideBgRunnable = () -> {
                if (playerView != null) playerView.setVisibility(View.INVISIBLE);
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            };
        }

        if (!show || path == null) {
            bgHandler.removeCallbacks(hideBgRunnable);
            bgHandler.postDelayed(hideBgRunnable, 200);
            return;
        }

        bgHandler.removeCallbacks(hideBgRunnable);

        if (path.equals(currentBgPath)) {
            if (isVideo) {
                if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
                if (playerView != null) playerView.setVisibility(View.VISIBLE);
                if (exoPlayer != null && !exoPlayer.isPlaying()) exoPlayer.play();
            } else {
                if (playerView != null) playerView.setVisibility(View.INVISIBLE);
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                if (globalImageView != null) {
                    globalImageView.setVisibility(View.VISIBLE);
                    File file = new File(path);
                    Glide.with(this).load(file).centerCrop().into(globalImageView);
                }
            }
            return;
        }

        currentBgPath = path;
        File file = new File(path);
        if (!file.exists()) return;

        if (isVideo) {
            if (globalImageView != null) globalImageView.setVisibility(View.INVISIBLE);
            if (playerView != null) playerView.setVisibility(View.VISIBLE);
            MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(file));
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.play();
        } else {
            if (playerView != null) playerView.setVisibility(View.INVISIBLE);
            if (exoPlayer != null) exoPlayer.pause();
            if (globalImageView != null) {
                globalImageView.setVisibility(View.VISIBLE);
                Glide.with(this).load(file).centerCrop().into(globalImageView);
            }
        }
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
        
        String path = prefs.getString("custom_bg_path", null);
        if (path != null) {
            boolean isVideo = prefs.getBoolean("custom_bg_is_video", false);
            if (isVideo && playerView != null && playerView.getVisibility() == View.VISIBLE) {
                if (exoPlayer != null && !exoPlayer.isPlaying()) exoPlayer.play();
            } else if (!isVideo && globalImageView != null && globalImageView.getVisibility() == View.VISIBLE) {
                Glide.with(this).load(new File(path)).centerCrop().into(globalImageView);
            }
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
                hideLoginScreen(); 
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
            Bitmap cachedAvatar = mMemoryCache.get("avatar_" + account.getId());
            if (cachedAvatar != null) {
                iconProfile.setImageTintList(null); 
                Glide.with(this).load(cachedAvatar).circleCrop().into(iconProfile);
            } else {
                File file = new File(getFilesDir(), "avatar_" + account.getId() + ".png");
                if (file.exists()) {
                    iconProfile.setImageTintList(null); 
                    Glide.with(this).load(file).circleCrop().into(iconProfile);
                } else {
                    iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
                    iconProfile.setImageResource(R.drawable.ic_nav_profile); 
                }
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

    // =========================================================================
    // БАГФИКС "ПРОСКАКИВАЮЩЕГО" ЭКРАНА И СЛОМАННОЙ НАВИГАЦИИ
    // =========================================================================
    private void checkAuthAndLoad(int tabIndex) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        
        // 1. Всегда переключаем фрагмент! Даже если пользователь не авторизован, 
        // под плашкой регистрации обязан лежать пустой Профиль или Поиск, а не старый экран!
        if (tabIndex == 1) {
            navigator.switchScreen(1, null); 
        } else if (tabIndex == 4) {
            if (account != null) StatsHelper.syncUserProfile(MainActivity.this);
            // Если нет аккаунта, передаем пустую строку, чтобы фрагмент просто отрисовал базу
            navigator.switchScreen(4, account != null ? account.getId() : ""); 
        }
        
        syncHeaderState(); 

        // 2. А уже поверх честно переключенного фрагмента накидываем заглушку
        if (account == null) {
            showLoginScreen(); 
        } else {
            hideLoginScreen(); 
        }
    } 

    public void hideLoginScreen() {
        final View loginView = container.findViewWithTag("login_screen_overlay");
        if (loginView != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            loginView.animate()
                    .translationX(screenWidth)
                    .setDuration(300)
                    .withEndAction(() -> container.removeView(loginView))
                    .start();
        } 
    }

    public void showLoginScreen() {
        mainHeader.setVisibility(View.VISIBLE);
        headerManager.resetHeader();
        
        // Если заглушка уже есть в контейнере, но просто спрятана (GONE) - возвращаем её!
        View existingView = container.findViewWithTag("login_screen_overlay");
        if (existingView != null) {
            existingView.setVisibility(View.VISIBLE);
            existingView.animate().translationX(0).setDuration(300).start();
            return; 
        }
        
        View view = getLayoutInflater().inflate(R.layout.layout_login_required, container, false);
        view.setClickable(true);
        view.setTag("login_screen_overlay"); 
        
        Button btn = view.findViewById(R.id.btn_login_center);
        btn.setOnClickListener(v -> startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN));
        
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        view.setTranslationX(screenWidth);
        container.addView(view);
        
        view.animate().translationX(0).setDuration(300).start();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                final GoogleSignInAccount acct = task.getResult(ApiException.class);
                VpsApi.authenticateWithGoogle(MainActivity.this, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String ourServerToken) {
                        vpsToken = ourServerToken;
                        loadUserAvatarToBottomNav(); 
                        updateNavState(4);
                        StatsHelper.syncUserProfile(MainActivity.this);
                        navigator.switchScreen(4, acct.getId()); 
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

        if (requestCode == RC_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            try {
                InputStream inputStream = getContentResolver().openInputStream(data.getData());
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true);
                final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
                if (acct == null) return;
                
                String filename = "avatar_" + acct.getId() + ".png";
                FileOutputStream outputStream = openFileOutput(filename, MODE_PRIVATE);
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);
                outputStream.close();
                inputStream.close();
                
                mMemoryCache.put("avatar_" + acct.getId(), scaled);
                loadUserAvatarToBottomNav(); 
                
                ImageView preview = findViewById(R.id.edit_avatar_preview);
                if (preview != null) Glide.with(MainActivity.this).load(scaled).circleCrop().into(preview);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                final String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                
                VpsApi.authenticateWithGoogle(MainActivity.this, acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        vpsToken = token;
                        VpsApi.saveUser(vpsToken, null, null, base64, 0, null, new VpsApi.Callback() {
                            @Override public void onSuccess(String s) {}
                            @Override public void onError(String s) {}
                        });
                    }
                    @Override public void onError(String e) {}
                });
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (requestCode == 9003 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                android.net.Uri selectedFileUri = data.getData();
                android.database.Cursor cursor = getContentResolver().query(selectedFileUri, null, null, null, null);
                long fileSize = 0;
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                    cursor.close();
                }

                if (fileSize > 30 * 1024 * 1024) {
                    Toast.makeText(this, getString(R.string.toast_file_too_large), Toast.LENGTH_LONG).show();
                    return; 
                }

                String mimeType = getContentResolver().getType(selectedFileUri);
                boolean isVideo = (mimeType != null && mimeType.startsWith("video/"));
                boolean isGif = (mimeType != null && mimeType.contains("gif"));
                
                if (mimeType == null) {
                    String pathLower = selectedFileUri.toString().toLowerCase();
                    isVideo = pathLower.contains(".mp4") || pathLower.contains(".mkv") || pathLower.contains(".avi") || pathLower.contains(".mov");
                    isGif = pathLower.contains(".gif");
                }
                
                String oldPath = prefs.getString("custom_bg_path", null);
                if (oldPath != null) {
                    File oldFile = new File(oldPath);
                    if (oldFile.exists()) oldFile.delete();
                }

                String extension = isVideo ? ".mp4" : (isGif ? ".gif" : ".jpg"); 
                String backgroundFileName = "profile_bg_" + System.currentTimeMillis() + extension;
                File outFile = new File(getFilesDir(), backgroundFileName);
                
                java.io.InputStream inputStream = getContentResolver().openInputStream(selectedFileUri);
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.close();
                inputStream.close();
                
                prefs.edit()
                    .putString("custom_bg_path", outFile.getAbsolutePath())
                    .putBoolean("custom_bg_is_video", isVideo)
                    .apply();
                    
                Toast.makeText(this, getString(R.string.toast_bg_success), Toast.LENGTH_SHORT).show();
                updateGlobalBackground(true);

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, getString(R.string.toast_bg_error), Toast.LENGTH_SHORT).show();
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
