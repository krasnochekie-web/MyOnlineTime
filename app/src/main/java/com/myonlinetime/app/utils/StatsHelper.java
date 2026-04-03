package com.myonlinetime.app.utils;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import androidx.fragment.app.Fragment;
import com.myonlinetime.app.ui.ProfileFragment; 
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsHelper {

    // ТЕХНИЧЕСКИЕ КОНСТАНТЫ
    private static final String PKG_ANDROID = "android";
    private static final String PKG_SYSTEM_UI = "com.android.systemui";
    private static final String PKG_GMS = "com.google.android.gms";
    private static final String PKG_SETTINGS = "com.android.settings";
    private static final String PKG_SETUP_WIZARD = "com.google.android.setupwizard";

    // 1. МЕТОД ДЛЯ ФОНОВОЙ СИНХРОНИЗАЦИИ С СЕРВЕРОМ
    public static void syncUserProfile(final MainActivity activity) {
        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return;
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
            UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
            final Map<String, Long> exactTimes = new HashMap<>();
            long totalMillis = 0;
            
            if (usm != null) {
                List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now);
                if (statsList != null) {
                    for (UsageStats stats : statsList) {
                        long time = stats.getTotalTimeInForeground();
                        if (time > 0) {
                            String pkg = stats.getPackageName();
                            Long current = exactTimes.get(pkg);
                            exactTimes.put(pkg, (current == null ? 0 : current) + time);
                        }
                    }
                }
            }
            
            PackageManager pm = activity.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
            Set<String> userApps = new HashSet<>();
            for (android.content.pm.ResolveInfo info : resolvedInfos) {
                userApps.add(info.activityInfo.packageName);
            }
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            String launcherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
            
            List<String> finalList = new ArrayList<>();
            for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
                String pkg = entry.getKey();
                long time = entry.getValue();
                boolean isSystemTrash = pkg.equals(PKG_ANDROID) || pkg.equals(PKG_SYSTEM_UI) || 
                                        pkg.equals(PKG_GMS) || pkg.equals(PKG_SETTINGS) || 
                                        pkg.equals(PKG_SETUP_WIZARD) || pkg.equals(launcherPkg);
                                        
                if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                    finalList.add(pkg);
                    totalMillis += time; 
                }
            }
            
            Collections.sort(finalList, (left, right) -> {
                Long tLeft = exactTimes.get(left);
                Long tRight = exactTimes.get(right);
                return Long.compare(tRight != null ? tRight : 0L, tLeft != null ? tLeft : 0L);
            });
            
            final Map<String, Long> finalTopApps = new HashMap<>();
            int limit = 0;
            for (String pkg : finalList) {
                if (limit++ >= 10) break;
                finalTopApps.put(pkg, exactTimes.get(pkg));
            }
            
            final long finalTime = totalMillis;
            
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.vpsToken != null) {
                     VpsApi.saveUser(activity.vpsToken, null, null, null, finalTime, finalTopApps, null);
                } else {
                     // ИСПРАВЛЕН ВЫЗОВ: Добавлен activity (Context)
                     VpsApi.authenticateWithGoogle(activity, account.getIdToken(), new VpsApi.LoginCallback() {
                         @Override
                         public void onSuccess(String token) {
                             activity.vpsToken = token;
                             VpsApi.saveUser(activity.vpsToken, null, null, null, finalTime, finalTopApps, null);
                         }
                         @Override public void onError(String error) {}
                     });
                }
            });
        });
    }

    // 2. МЕТОД ДЛЯ ОТРИСОВКИ ТОП-10 В ПРОФИЛЕ
    public static void loadStatsToProfile(final MainActivity activity, final TextView weekTimeText, final LinearLayout appsContainer) {
        appsContainer.removeAllViews();
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
            UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
            final Map<String, Long> exactTimes = new HashMap<>();
            long tempTotalMillis = 0;
            
            if (usm != null) {
                List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now);
                if (statsList != null) {
                    for (UsageStats stats : statsList) {
                        long time = stats.getTotalTimeInForeground();
                        if (time > 0) {
                            String pkg = stats.getPackageName();
                            Long current = exactTimes.get(pkg);
                            exactTimes.put(pkg, (current == null ? 0 : current) + time);
                        }
                    }
                }
            }
            
            PackageManager pm = activity.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
            Set<String> userApps = new HashSet<>();
            for (android.content.pm.ResolveInfo info : resolvedInfos) {
                userApps.add(info.activityInfo.packageName);
            }
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            String launcherPkg = defaultLauncher != null ? defaultLauncher.activityInfo.packageName : "";
            
            final List<String> finalList = new ArrayList<>();
            for (Map.Entry<String, Long> entry : exactTimes.entrySet()) {
                String pkg = entry.getKey();
                long time = entry.getValue();
                boolean isSystemTrash = pkg.equals(PKG_ANDROID) || pkg.equals(PKG_SYSTEM_UI) || 
                                        pkg.equals(PKG_GMS) || pkg.equals(PKG_SETTINGS) || 
                                        pkg.equals(PKG_SETUP_WIZARD) || pkg.equals(launcherPkg);
                                        
                if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                    finalList.add(pkg);
                    tempTotalMillis += time; 
                }
            }
            
            Collections.sort(finalList, (left, right) -> {
                Long tLeft = exactTimes.get(left);
                Long tRight = exactTimes.get(right);
                return Long.compare(tRight != null ? tRight : 0L, tLeft != null ? tLeft : 0L);
            });

            final long finalTotalMillis = tempTotalMillis;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;

                long minutes = finalTotalMillis / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;
                
                // РАЗХАРДКОРЕНО: Используем строковые ресурсы для времени
                if (weekTimeText != null) {
                    String timeStr = (hours > 0) 
                        ? activity.getString(R.string.format_hours_mins, hours, mins) 
                        : activity.getString(R.string.format_mins, mins);
                    weekTimeText.setText(timeStr);
                }
                
                int limit = 0;
                for (String pkg : finalList) {
                    if (limit >= 10) break;
                    
                    View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, appsContainer, false);
                    
                    if (limit >= 2) {
                        view.setVisibility(View.GONE);
                    }
                    ImageView iconView = view.findViewById(R.id.app_icon);
                    TextView nameView = view.findViewById(R.id.app_name);
                    TextView timeView = view.findViewById(R.id.app_time);
                    
                    try {
                        ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                        nameView.setText(pm.getApplicationLabel(appInfo));
                        iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
                    } catch (Exception e) { nameView.setText(pkg); }
                    
                    timeView.setText(Utils.formatTime(activity, exactTimes.get(pkg)));
                    appsContainer.addView(view);

                    if (activity.getSupportFragmentManager() != null) {
                        for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                            if (f instanceof ProfileFragment) {
                                ((ProfileFragment) f).setupOwnerAppInteractions(activity, view, pkg);
                                break;
                            }
                        }
                    }
                    limit++;
                }
                
                View btnExpand = ((View)appsContainer.getParent()).findViewById(R.id.btn_expand_apps);
                if (btnExpand != null) {
                    btnExpand.setVisibility(limit > 2 ? View.VISIBLE : View.GONE);
                }
            });
        });
    }
}
