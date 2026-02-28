package com.mynewtime.app;

import androidx.appcompat.app.AppCompatActivity;

import com.mynewtime.app.utils.StatsHelper;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import com.bumptech.glide.Glide;
import android.util.LruCache;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import com.mynewtime.app.models.User;
import com.mynewtime.app.utils.Utils;
import com.mynewtime.app.adapters.AppsAdapter;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    public FrameLayout container;
    public View mainHeader;
    private View bottomNav;
    public TextView headerTitle;
    public ImageView headerBackBtn;

    private ImageView iconFeed, iconSearch, iconUsage, iconProfile;
    private TextView textFeed, textSearch, textUsage, textProfile;
    private int currentTab = 0;

    public GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 9001;
    private static final int RC_PICK_IMAGE = 9002;
    
    public SharedPreferences prefs;
    public LruCache<String, Bitmap> mMemoryCache;
    
    private String lastSearchQuery = "";
    public String vpsToken = null;
    public com.mynewtime.app.utils.AppNavigator navigator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_main);
    navigator = new com.mynewtime.app.utils.AppNavigator(this, R.id.fragment_container);
        
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
        headerTitle = (TextView) findViewById(R.id.header_title);
        headerBackBtn = (ImageView) findViewById(R.id.header_back_btn);
        bottomNav = (View) findViewById(R.id.bottom_nav_container);
        
        iconFeed = (ImageView) findViewById(R.id.icon_feed); 
        iconSearch = (ImageView) findViewById(R.id.icon_search);
        iconUsage = (ImageView) findViewById(R.id.icon_usage); 
        iconProfile = (ImageView) findViewById(R.id.icon_profile);
        
        textFeed = (TextView) findViewById(R.id.text_feed); 
        textSearch = (TextView) findViewById(R.id.text_search);
        textUsage = (TextView) findViewById(R.id.text_usage); 
        textProfile = (TextView) findViewById(R.id.text_profile);

        findViewById(R.id.nav_feed).setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { updateNavState(0); navigator.switchScreen(0, null); resetHeader(); }
        });
        findViewById(R.id.nav_search).setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { updateNavState(1); checkAuthAndLoad(1); }
        });
        findViewById(R.id.nav_usage).setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { updateNavState(3); navigator.switchScreen(3, null); resetHeader(); }
        });
        findViewById(R.id.nav_profile).setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { updateNavState(4); checkAuthAndLoad(4); }
        });

        headerBackBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBackNavigation();
            }
        });

        updateNavState(0);
        resetHeader();
        
        mGoogleSignInClient.silentSignIn().addOnCompleteListener(this, new com.google.android.gms.tasks.OnCompleteListener<GoogleSignInAccount>() {
            @Override
            public void onComplete(Task<GoogleSignInAccount> task) {
                // Как только токен обновлен - запускаем синхронизацию!
                StatsHelper.syncUserProfile(MainActivity.this);
            }
        });
    } 
    
    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }
    private void handleBackNavigation() {
        if (headerBackBtn.getVisibility() == View.VISIBLE) {
            // Если мы в глубине навигации (редактор, чужой профиль)
            GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(MainActivity.this);
            if (currentTab == 4) { 
                if (acct != null) navigator.switchScreen(4, acct.getId()); // Возврат к своему профилю
            } else {
                navigator.switchScreen(1, null); // Возврат к списку поиска
            }
            resetHeader();
        } else if (currentTab != 0) {
            // Если мы на любой вкладке кроме главной -> идем на главную
            updateNavState(0);
            navigator.switchScreen(0, null); // Переключаем на главную
            resetHeader();
        } else {
            // Если мы на главной -> выход
            super.onBackPressed();
        }
    }

    public void resetHeader() {
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextSize(22);
        headerBackBtn.setVisibility(View.GONE);
    }
    private void checkAuthAndLoad(int tabIndex) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            showLoginScreen();
        } else {
            if (tabIndex == 1) navigator.switchScreen(1, null); // Изменили тут
            if (tabIndex == 4) {
                resetHeader();
                StatsHelper.syncUserProfile(MainActivity.this); 
                navigator.switchScreen(4, account.getId()); // И тут
            }
        }
    }

    public void showLoginScreen() {
        mainHeader.setVisibility(View.VISIBLE);
        resetHeader();
        
        if (container.findViewById(R.id.btn_login_center) != null) return;
        
        View view = getLayoutInflater().inflate(R.layout.layout_login_required, container, false);
        view.setBackgroundColor(0xCC000000); 
        view.setClickable(true); 
        
        Button btn = (Button) view.findViewById(R.id.btn_login_center);
        btn.setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) { 
                startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN); 
            }
        });
        container.addView(view);
    }

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
                        updateNavState(4); 
                        StatsHelper.syncUserProfile(MainActivity.this);
                        navigator.switchScreen(4, acct.getId()); // Вот эта строчка поменялась!
                    }
                    @Override public void onError(String error) { 
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
                
                // СТАЛО:
                ImageView preview = (ImageView) findViewById(R.id.edit_avatar_preview);
                if (preview != null) {
                    Glide.with(MainActivity.this).load(scaled).circleCrop().into(preview);
                  }
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                final String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                
                VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                    @Override
                    public void onSuccess(String token) {
                        vpsToken = token;
                        VpsApi.saveUser(vpsToken, null, null, base64, 0, null, new VpsApi.Callback() {
                            @Override public void onSuccess(String s) {
                                Toast.makeText(MainActivity.this, getString(R.string.msg_avatar_saved), Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onError(String s) {
                                Toast.makeText(MainActivity.this, getString(R.string.err_server) + s, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                    @Override public void onError(String e) {
                        Toast.makeText(MainActivity.this, getString(R.string.err_auth) + e, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) { 
                e.printStackTrace(); 
                Toast.makeText(MainActivity.this, getString(R.string.err_image_processing), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadCustomAvatar(ImageView imageView, String uid) {
        try {
            File file = new File(getFilesDir(), "avatar_" + uid + ".png");
            if (file.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                mMemoryCache.put("avatar_" + uid, bmp);
                Glide.with(this).load(bmp).circleCrop().into(imageView);
            }
        } catch (Exception e) {}
    }
    
    private String getLocalAvatarAsBase64(String uid) {
        try {
            File file = new File(getFilesDir(), "avatar_" + uid + ".png");
            if (!file.exists()) return null;
            Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 70, baos); 
            return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
        } catch (Exception e) { return null; }
    }
    
    private void updateNavState(int index) {
        currentTab = index;
        mainHeader.setVisibility(View.VISIBLE);
        bottomNav.setVisibility(View.VISIBLE);

        // Этот код автоматически применяет нужный цвет из твоего XML файла
        iconFeed.setSelected(index == 0);
        textFeed.setSelected(index == 0);

        iconSearch.setSelected(index == 1);
        textSearch.setSelected(index == 1);

        iconUsage.setSelected(index == 3);
        textUsage.setSelected(index == 3);

        iconProfile.setSelected(index == 4);
        textProfile.setSelected(index == 4);
    }

    private boolean hasPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
    
    private List<UsageStats> getCleanUsageStats(long start, long end) {
        UsageStatsManager usm = (UsageStatsManager) getSystemService("usagestats");
        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(start, end);
        List<UsageStats> cleanList = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (UsageStats stat : statsMap.values()) {
            if (stat.getTotalTimeInForeground() < 1000) continue;
            if (stat.getLastTimeUsed() < start) continue;
            if (pm.getLaunchIntentForPackage(stat.getPackageName()) == null) continue;
            cleanList.add(stat);
        }
        Collections.sort(cleanList, new Comparator<UsageStats>() {
            @Override
            public int compare(UsageStats a, UsageStats b) {
                return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground());
            }
        });
        return cleanList;
    }
} 