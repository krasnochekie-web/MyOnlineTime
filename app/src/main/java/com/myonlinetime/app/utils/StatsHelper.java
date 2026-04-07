package com.myonlinetime.app.utils;

import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.ui.ProfileFragment; 

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsHelper {

    // 1. МЕТОД ДЛЯ ФОНОВОЙ СИНХРОНИЗАЦИИ С СЕРВЕРОМ
    public static void syncUserProfile(final MainActivity activity) {
        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return;
        
        // ИСПОЛЬЗУЕМ ГЛОБАЛЬНЫЙ ПУЛ (Вместо создания новых потоков)
        Utils.backgroundExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
            // === МАГИЯ USAGE MATH ===
            // Получаем уже отфильтрованные данные за 1 строчку
            final Map<String, Long> exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_DAILY, startTime, now);
            final long finalTime = UsageMath.sumMap(exactTimes);
            
            List<String> finalList = new ArrayList<>(exactTimes.keySet());
            Collections.sort(finalList, (left, right) -> Long.compare(exactTimes.get(right), exactTimes.get(left)));
            
            final Map<String, Long> finalTopApps = new HashMap<>();
            int limit = 0;
            for (String pkg : finalList) {
                if (limit++ >= 10) break;
                finalTopApps.put(pkg, exactTimes.get(pkg));
            }
            
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;

                if (activity.vpsToken != null) {
                     VpsApi.saveUser(activity.vpsToken, null, null, null, finalTime, finalTopApps, null);
                } else {
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
        
        Utils.backgroundExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
            // === МАГИЯ USAGE MATH ===
            final Map<String, Long> exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_DAILY, startTime, now);
            final long finalTotalMillis = UsageMath.sumMap(exactTimes);
            
            final List<String> finalList = new ArrayList<>(exactTimes.keySet());
            Collections.sort(finalList, (left, right) -> Long.compare(exactTimes.get(right), exactTimes.get(left)));

            PackageManager pm = activity.getPackageManager();

            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;

                long minutes = finalTotalMillis / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;
                
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
