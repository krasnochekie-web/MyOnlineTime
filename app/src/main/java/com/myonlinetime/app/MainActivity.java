package com.myonlinetime.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.widget.*;

import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.AppNavigator;

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

public class MainActivity extends AppCompatActivity {

    // --- UI Элементы ---
    public FrameLayout container;
    public View mainRoot, mainHeader, bottomNav, permissionOverlay;
    public TextView headerTitle;
    public ImageView headerBackBtn;
    private ImageView iconFeed, iconSearch, iconUsage, iconProfile, iconSettings;

    // --- Состояние и Навигация ---
    private int currentTab = 0;
    public AppNavigator navigator;

    // --- Авторизация и Кэш ---
    public GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 9001;
    private static final int RC_PICK_IMAGE = 9002;
    public SharedPreferences prefs;
    public LruCache<String, Bitmap> mMemoryCache;
    public String vpsToken = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Проверка темы ДО отрисовки интерфейса
        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        int savedTheme = appPrefs.getInt("selected_theme", AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(savedTheme);

        super.onCreate(savedInstanceState);
        setupWindow();
        setContentView(R.layout.activity_main);

        // Инициализация разбита на логические блоки во избежание God Object
        initCache();
        initViews();
        initNavigation();
        initAuth();
    }

    // =========================================================================
    // 1. ИНИЦИАЛИЗАЦИЯ
    // =========================================================================

    private void setupWindow() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
    }

    private void initCache() {
        prefs = getSharedPreferences("UserProfile", MODE_PRIVATE);
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount() / 1024; }
        };
    }

    private void initViews() {
        mainRoot = findViewById(R.id.main_root);
        container = findViewById(R.id.fragment_container);
        mainHeader = findViewById(R.id.app_header);
        headerTitle = findViewById(R.id.header_title);
        headerBackBtn = findViewById(R.id.header_back_btn);
        bottomNav = findViewById(R.id.bottom_nav_container);
        permissionOverlay = findViewById(R.id.permission_overlay);

        iconFeed = findViewById(R.id.icon_feed); 
        iconSearch = findViewById(R.id.icon_search);
        iconProfile = findViewById(R.id.icon_profile); 
        iconUsage = findViewById(R.id.icon_usage); 
        iconSettings = findViewById(R.id.icon_settings); 

        // Скругление шапки
        final float cornerRadius = 24f * getResources().getDisplayMetrics().density;
        mainHeader.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), (int) (view.getHeight() + cornerRadius), cornerRadius);
            }
        });
        mainHeader.setClipToOutline(true);

        if (permissionOverlay != null) {
            Button btnGrant = permissionOverlay.findViewById(R.id.btn_grant_permission);
            if (btnGrant != null) {
                btnGrant.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            }
        }
    }

    private void initNavigation() {
        navigator = new AppNavigator(this, R.id.fragment_container);

        findViewById(R.id.nav_feed).setOnClickListener(v -> navigateToTab(0, true));
        findViewById(R.id.nav_search).setOnClickListener(v -> checkAuthAndLoad(1));
        findViewById(R.id.nav_usage).setOnClickListener(v -> navigateToTab(3, true));
        findViewById(R.id.nav_profile).setOnClickListener(v -> checkAuthAndLoad(4));
        findViewById(R.id.nav_settings).setOnClickListener(v -> navigateToTab(5, false));

        headerBackBtn.setOnClickListener(v -> handleBackNavigation());

        updateNavState(0);
        resetHeader();
    }

    private void initAuth() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("603306715003-0ptgu4fqnldcsoon9niprvi772m2ebks.apps.googleusercontent.com") 
                .requestEmail().build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        mGoogleSignInClient.silentSignIn().addOnCompleteListener(this, task -> {
            StatsHelper.syncUserProfile(MainActivity.this);
            loadUserAvatarToBottomNav(); 
        });
    }

    // =========================================================================
    // 2. НАВИГАЦИЯ И СОСТОЯНИЕ UI
    // =========================================================================

    private void navigateToTab(int tabIndex, boolean hideLogin) {
        if (hideLogin) hideLoginScreen();
        updateNavState(tabIndex);
        navigator.switchScreen(tabIndex, null);
        if (tabIndex != 5) resetHeader(); // Настройки (5) сами управляют шапкой
    }

    private void checkAuthAndLoad(int tabIndex) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            showLoginScreen(); 
        } else {
            hideLoginScreen(); 
            updateNavState(tabIndex);
            if (tabIndex == 1) navigator.switchScreen(1, null); 
            if (tabIndex == 4) {
                resetHeader();
                StatsHelper.syncUserProfile(MainActivity.this);
                navigator.switchScreen(4, account.getId()); 
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
        iconUsage.setSelected(index == 3);
        iconProfile.setSelected(index == 4); 
        iconSettings.setSelected(index == 5); 
    }

    public void resetHeader() {
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextSize(20);
        headerBackBtn.setVisibility(View.GONE);
    }

    private void showBottomNav() {
        if (bottomNav != null) bottomNav.animate().translationY(0).setDuration(250).start();
    }

    private void hideBottomNav() {
        if (bottomNav != null) bottomNav.animate().translationY(bottomNav.getHeight() + 50).setDuration(250).start();
    }

    // =========================================================================
    // 3. ЭКРАН ВХОДА С АНИМАЦИЯМИ (СЛОЙ ПОВЕРХ)
    // =========================================================================

    public void showLoginScreen() {
        mainHeader.setVisibility(View.VISIBLE);
        resetHeader();
        if (container.findViewWithTag("login_screen_overlay") != null) return; 
        
        View view = getLayoutInflater().inflate(R.layout.layout_login_required, container, false);
        view.setClickable(true);
        view.setTag("login_screen_overlay"); 
        
        Button btn = view.findViewById(R.id.btn_login_center);
        btn.setOnClickListener(v -> startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN));
        
        // АНИМАЦИЯ ПОЯВЛЕНИЯ (выезжает справа)
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        view.setTranslationX(screenWidth); 
        container.addView(view); 

        view.animate()
                .translationX(0)
                .setDuration(300)
                .start();
    }

    public void hideLoginScreen() {
        final View loginView = container.findViewWithTag("login_screen_overlay");
        if (loginView != null) {
            // АНИМАЦИЯ ЗАКРЫТИЯ (уезжает вправо)
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            loginView.animate()
                    .translationX(screenWidth)
                    .setDuration(300)
                    .withEndAction(() -> container.removeView(loginView))
                    .start();
        }
    }

    // =========================================================================
    // 4. ЖИЗНЕННЫЙ ЦИКЛ И РАЗРЕШЕНИЯ
    // =========================================================================

    @Override
    protected void onResume() {
        super.onResume();
        loadUserAvatarToBottomNav(); 
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
    }

    @Override
    public void onBackPressed() { handleBackNavigation(); }    

    private void handleBackNavigation() {
        if (navigator.closeSubScreen()) {
            resetHeader();
            return; 
        }
        if (currentTab != 0) {
            navigateToTab(0, true);
        } else {
            super.onBackPressed();
        }
    }

    private boolean hasPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // =========================================================================
    // 5. РАБОТА С ДАННЫМИ (АВАТАРКИ И РЕЗУЛЬТАТЫ INTENT)
    // =========================================================================

    private void loadUserAvatarToBottomNav() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null && iconProfile != null) {
            Bitmap cachedAvatar = mMemoryCache.get("avatar_" + account.getId());
            if (cachedAvatar != null) {
                Glide.with(this).load(cachedAvatar).circleCrop().into(iconProfile);
            } else {
                File file = new File(getFilesDir(), "avatar_" + account.getId() + ".png");
                if (file.exists()) {
                    Glide.with(this).load(file).circleCrop().into(iconProfile);
                } else {
                    iconProfile.setImageResource(R.drawable.ic_nav_profile); 
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                final GoogleSignInAccount acct = task.getResult(ApiException.class);
                com.myonlinetime.app.VpsApi.authenticateWithGoogle(acct.getIdToken(), new com.myonlinetime.app.VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String ourServerToken) {
                        vpsToken = ourServerToken;
                        loadUserAvatarToBottomNav(); 
                        updateNavState(4);
                        StatsHelper.syncUserProfile(MainActivity.this);
                        navigator.switchScreen(4, acct.getId()); 
                        
                        // Если экран логина всё еще висит поверх - убираем его
                        hideLoginScreen();
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
                if(inputStream != null) inputStream.close();
                
                mMemoryCache.put("avatar_" + acct.getId(), scaled);
                loadUserAvatarToBottomNav(); 
                
                ImageView preview = findViewById(R.id.edit_avatar_preview);
                if (preview != null) Glide.with(MainActivity.this).load(scaled).circleCrop().into(preview);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                final String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                
                com.myonlinetime.app.VpsApi.authenticateWithGoogle(acct.getIdToken(), new com.myonlinetime.app.VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        vpsToken = token;
                        com.myonlinetime.app.VpsApi.saveUser(vpsToken, null, null, base64, 0, null, new com.myonlinetime.app.VpsApi.Callback() {
                            @Override public void onSuccess(String s) {}
                            @Override public void onError(String s) {}
                        });
                    }
                    @Override public void onError(String e) {}
                });
            } catch (Exception e) { e.printStackTrace(); }
        }
    }
}
