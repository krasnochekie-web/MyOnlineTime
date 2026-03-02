package com.myonlinetime.app.utils;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StatsHelper {

    // 1. МЕТОД ДЛЯ ФОНОВОЙ СИНХРОНИЗАЦИИ С СЕРВЕРОМ
    public static void syncUserProfile(final MainActivity activity) {
        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return;
        
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance(); 
        cal.add(Calendar.DAY_OF_YEAR, -7); 
        long startTime = cal.getTimeInMillis();

        UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
        final Map<String, Long> exactTimes = new HashMap<>();
        long totalMillis = 0;

        if (usm != null) {
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, startTime, now);
            if (stats != null) {
                for (UsageStats stat : stats) {
                    long time = stat.getTotalTimeInForeground();
                    if (time > 0) exactTimes.put(stat.getPackageName(), exactTimes.getOrDefault(stat.getPackageName(), 0L) + time);
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
            boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                    pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                    pkg.equals("com.google.android.setupwizard") || pkg.equals(launcherPkg);
                                    
            if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                finalList.add(pkg);
                totalMillis += time;
            }
        }

        Collections.sort(finalList, new Comparator<String>() {
            public int compare(String left, String right) {
                Long tLeft = exactTimes.get(left);
                Long tRight = exactTimes.get(right);
                if (tLeft == null) tLeft = 0L;
                if (tRight == null) tRight = 0L;
                return Long.compare(tRight, tLeft);
            }
        });

        final Map<String, Long> finalTopApps = new HashMap<>();
        int limit = 0;
        for (String pkg : finalList) {
            if (limit++ >= 10) break;
            finalTopApps.put(pkg, exactTimes.get(pkg));
        }
        
        final long finalTime = totalMillis;

        if (activity.vpsToken != null) {
             VpsApi.saveUser(activity.vpsToken, null, null, null, finalTime, finalTopApps, null);
        } else {
             VpsApi.authenticateWithGoogle(account.getIdToken(), new VpsApi.LoginCallback() {
                 @Override
                 public void onSuccess(String token) {
                     activity.vpsToken = token;
                     VpsApi.saveUser(activity.vpsToken, null, null, null, finalTime, finalTopApps, null);
                 }
                 @Override public void onError(String error) {}
             });
        }
    }

    // 2. МЕТОД ДЛЯ ОТРИСОВКИ ТОП-10 В ПРОФИЛЕ
    public static void loadStatsToProfile(final MainActivity activity, TextView weekTimeText, LinearLayout appsContainer) {
        appsContainer.removeAllViews();
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance(); 
        cal.add(Calendar.DAY_OF_YEAR, -7); 
        long startTime = cal.getTimeInMillis();

        UsageStatsManager usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
        final Map<String, Long> exactTimes = new HashMap<>();
        long totalMillis = 0;

        if (usm != null) {
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, startTime, now);
            if (stats != null) {
                for (UsageStats stat : stats) {
                    long time = stat.getTotalTimeInForeground();
                    if (time > 0) exactTimes.put(stat.getPackageName(), exactTimes.getOrDefault(stat.getPackageName(), 0L) + time);
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
            boolean isSystemTrash = pkg.equals("android") || pkg.equals("com.android.systemui") || 
                                    pkg.equals("com.google.android.gms") || pkg.equals("com.android.settings") || 
                                    pkg.equals("com.google.android.setupwizard") || pkg.equals(launcherPkg);
                                    
            if (time > 0 && userApps.contains(pkg) && !isSystemTrash) {
                finalList.add(pkg);
                totalMillis += time;
            }
        }

        Collections.sort(finalList, new Comparator<String>() {
            public int compare(String left, String right) {
                Long tLeft = exactTimes.get(left);
                Long tRight = exactTimes.get(right);
                if (tLeft == null) tLeft = 0L;
                if (tRight == null) tRight = 0L;
                return Long.compare(tRight, tLeft);
            }
        });

        long minutes = totalMillis / 1000 / 60;
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (weekTimeText != null) {
            weekTimeText.setText(hours > 0 ? hours + " ч " + mins + " мин" : mins + " мин");
        }
        int limit = 0;
        for (String pkg : finalList) {
            if (limit >= 10) break; // Защита от бесконечности
            
            View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, appsContainer, false);
            
            // ПРЯЧЕМ ВСЕ ЭЛЕМЕНТЫ ПОСЛЕ ТРЕТЬЕГО
            if (limit >= 3) {
                view.setVisibility(View.GONE);
            }

            ImageView iconView = view.findViewById(R.id.app_icon);
            TextView nameView = view.findViewById(R.id.app_name);
            TextView timeView = view.findViewById(R.id.app_time);
            
            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                nameView.setText(pm.getApplicationLabel(appInfo));
                iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
            } catch (Exception e) { nameView.setText(pkg); }
            
            timeView.setText(Utils.formatTime(activity, exactTimes.get(pkg)));
            appsContainer.addView(view);
            limit++;
        }
        
        // Показываем кнопку-расширитель, если элементов больше 3
        View btnExpand = ((View)appsContainer.getParent()).findViewById(R.id.btn_expand_apps);
        if (btnExpand != null) {
            btnExpand.setVisibility(limit > 3 ? View.VISIBLE : View.GONE);
        }
    }
}