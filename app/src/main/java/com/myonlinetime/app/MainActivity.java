package com.myonlinetime.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate; // <-- ДОБАВЛЕН ИМПОРТ

import com.myonlinetime.app.utils.StatsHelper;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.*;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;

import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.Utils;
import com.myonlinetime.app.adapters.AppsAdapter;

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
import java.util.*;

public class MainActivity extends AppCompatActivity {

    public FrameLayout container;
    public View mainHeader;
    private View bottomNav;
    public View mainRoot;
    public TextView headerTitle;
    public ImageView headerBackBtn;
    
    // Иконки меню (добавили настройки)
    private ImageView iconFeed, iconSearch, iconUsage, iconProfile, iconSettings;
    
    private int currentTab = 0;

    public GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 9001;
    private static final int RC_PICK_IMAGE = 9002;
    
    public SharedPreferences prefs;
    public LruCache<String, Bitmap> mMemoryCache;
    
    public String vpsToken = null;
    public com.myonlinetime.app.utils.AppNavigator navigator;

    private View permissionOverlay;
    private float lastTouchY, lastTouchX;
    private boolean isBottomNavVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // --- ПРОВЕРКА ТЕМЫ ДО ОТРИСОВКИ ИНТЕРФЕЙСА ---
        SharedPreferences appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        int savedTheme = appPrefs.getInt("selected_theme", AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(savedTheme);
        // ----------------------------------------------

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
        
        iconFeed = (ImageView) findViewById(R.id.icon_feed); 
        iconSearch = (ImageView) findViewById(R.id.icon_search);
        iconProfile = (ImageView) findViewById(R.id.icon_profile); // Это теперь по центру
        iconUsage = (ImageView) findViewById(R.id.icon_usage); 
        iconSettings = (ImageView) findViewById(R.id.icon_settings); // Это новая справа

        permissionOverlay = findViewById(R.id.permission_overlay);
        if (permissionOverlay != null) {
            Button btnGrant = permissionOverlay.findViewById(R.id.btn_grant_permission);
            if (btnGrant != null) {
                btnGrant.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            }
        }

        // Логика кликов по меню
        findViewById(R.id.nav_feed).setOnClickListener(v -> {
            hideLoginScreen(); 
            updateNavState(0);
            navigator.switchScreen(0, null);
            resetHeader();
        });

        findViewById(R.id.nav_search).setOnClickListener(v -> { 
            updateNavState(1); 
            checkAuthAndLoad(1); 
        });
        
        // Вкладка 3: Профиль (Центральная) - индекс 4 остался старым, чтобы не ломать твой код
        findViewById(R.id.nav_profile).setOnClickListener(v -> { 
            updateNavState(4); 
            checkAuthAndLoad(4); 
        });

        findViewById(R.id.nav_usage).setOnClickListener(v -> {
            hideLoginScreen(); 
            updateNavState(3);
            navigator.switchScreen(3, null);
            resetHeader();
        });
        // Вкладка 5: Настройки
        findViewById(R.id.nav_settings).setOnClickListener(v -> {
            hideLoginScreen(); // Настройки доступны без заглушки входа
            updateNavState(5);
            
            // Вызываем через твой навигатор для правильной анимации перелистывания!
            // Никаких изменений шапки здесь не делаем.
            navigator.switchScreen(5, null);
        });
        headerBackBtn.setOnClickListener(v -> handleBackNavigation());

        updateNavState(0);
        resetHeader();
        
        // --- КОНЕЦ МЕТОДА onCreate ---
        mGoogleSignInClient.silentSignIn().addOnCompleteListener(this, task -> {
            StatsHelper.syncUserProfile(MainActivity.this);
            loadUserAvatarToBottomNav(); 
        });

    } 

    // --- СРАЗУ ПОСЛЕ НЕГО ИДЕТ МЕТОД ЗАГРУЗКИ АВАТАРКИ ---
private void loadUserAvatarToBottomNav() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        
        // --- Добавим обработку для гостя (когда account == null) ---
        if (account == null) {
            if (iconProfile != null) {
                // Красим иконку-заглушку в цвета нижнего меню (серая/бордовая)
                iconProfile.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_selector));
                iconProfile.setImageResource(R.drawable.ic_nav_profile);
            }
            return;
        }

        if (iconProfile != null) {
            Bitmap cachedAvatar = mMemoryCache.get("avatar_" + account.getId());
            if (cachedAvatar != null) {
                iconProfile.setImageTintList(null); // УБИРАЕМ краску для реального фото!
                Glide.with(this).load(cachedAvatar).circleCrop().into(iconProfile);
            } else {
                File file = new File(getFilesDir(), "avatar_" + account.getId() + ".png");
                if (file.exists()) {
                    iconProfile.setImageTintList(null); // УБИРАЕМ краску для реального фото!
                    Glide.with(this).load(file).circleCrop().into(iconProfile);
                } else {
                    // Если фото нет, красим стандартную иконку-заглушку
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
    protected void onResume() {
        super.onResume();
        loadUserAvatarToBottomNav(); // Обновляем картинку при возвращении
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
            updateNavState(0);
            navigator.switchScreen(0, null);
            resetHeader();
        } else {
            super.onBackPressed();
        }
    }

    public void resetHeader() {
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextSize(20);
        headerBackBtn.setVisibility(View.GONE);
    }

    private void checkAuthAndLoad(int tabIndex) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            showLoginScreen(); 
        } else {
            hideLoginScreen(); 
            if (tabIndex == 1) navigator.switchScreen(1, null); 
            if (tabIndex == 4) {
                resetHeader();
                StatsHelper.syncUserProfile(MainActivity.this);
                navigator.switchScreen(4, account.getId()); 
            }
        }
    } 

    // ==========================================================
    // ЗДЕСЬ ИЗМЕНЕНИЯ АНИМАЦИИ ДЛЯ ЭКРАНА ВХОДА
    // ==========================================================
    
    public void hideLoginScreen() {
        final View loginView = container.findViewWithTag("login_screen_overlay");
        if (loginView != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            // Анимация уезжания вправо
            loginView.animate()
                    .translationX(screenWidth)
                    .setDuration(300)
                    .withEndAction(() -> container.removeView(loginView))
                    .start();
        } 
    }

    public void showLoginScreen() {
        mainHeader.setVisibility(View.VISIBLE);
        resetHeader();
        if (container.findViewWithTag("login_screen_overlay") != null) return; 
        
        View view = getLayoutInflater().inflate(R.layout.layout_login_required, container, false);
        view.setClickable(true);
        view.setTag("login_screen_overlay"); 
        
        Button btn = view.findViewById(R.id.btn_login_center);
        btn.setOnClickListener(v -> startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN));
        
        // Готовим анимацию: ставим экран за правый край
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        view.setTranslationX(screenWidth);
        container.addView(view);
        
        // Запускаем выезд справа налево
        view.animate()
                .translationX(0)
                .setDuration(300)
                .start();
    }
    // ==========================================================

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                final GoogleSignInAccount acct = task.getResult(ApiException.class);
                VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String ourServerToken) {
                        vpsToken = ourServerToken;
                        loadUserAvatarToBottomNav(); // Загружаем картинку после входа
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
                loadUserAvatarToBottomNav(); // Обновляем картинку меню сразу после выбора!
                
                ImageView preview = findViewById(R.id.edit_avatar_preview);
                if (preview != null) Glide.with(MainActivity.this).load(scaled).circleCrop().into(preview);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                final String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                
                VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
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
    }

    private void updateNavState(int index) {
        currentTab = index;
        mainHeader.setVisibility(View.VISIBLE);
        mainHeader.bringToFront(); 
        
        if (permissionOverlay != null && permissionOverlay.getVisibility() == View.VISIBLE) {
            mainHeader.bringToFront();
        }

        bottomNav.setVisibility(View.VISIBLE);
        showBottomNav(); // Принудительно показываем меню при переключении вкладок

        iconFeed.setSelected(index == 0);
        iconSearch.setSelected(index == 1);
        iconProfile.setSelected(index == 4); // Центральная вкладка профиля
        iconUsage.setSelected(index == 3);
        iconSettings.setSelected(index == 5); // Вкладка настроек
    }

    private boolean hasPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
