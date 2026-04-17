package com.myonlinetime.app.utils;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.ui.ProfileFragment; 

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsHelper {

    // Класс-контейнер для предзагрузки тяжелых данных
    private static class AppData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        boolean isDeleted; // Флаг для корзины
    }

    // Парсер для совсем уж мертвых приложений, которых нет даже в Сейфе
    private static String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) {
            return pkg;
        }
    }

    // 1. МЕТОД ДЛЯ ФОНОВОЙ СИНХРОНИЗАЦИИ С СЕРВЕРОМ
    public static void syncUserProfile(final MainActivity activity) {
        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return;
        
        Utils.backgroundExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
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

    // 2. МЕТОД ДЛЯ ОТРИСОВКИ ТОП-10 В ПРОФИЛЕ (ОПТИМИЗИРОВАННЫЙ С ПОДДЕРЖКОЙ УДАЛЕННЫХ)
    public static void loadStatsToProfile(final MainActivity activity, final TextView weekTimeText, final LinearLayout appsContainer) {
        if (appsContainer != null) {
            appsContainer.setLayoutTransition(null);
            appsContainer.removeAllViews();
        }
        
        Utils.backgroundExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance(); 
            cal.add(Calendar.DAY_OF_YEAR, -7); 
            long startTime = cal.getTimeInMillis();
            
            final Map<String, Long> exactTimes = UsageMath.getFilteredStats(activity, UsageStatsManager.INTERVAL_DAILY, startTime, now);
            final long finalTotalMillis = UsageMath.sumMap(exactTimes);
            
            final List<String> finalList = new ArrayList<>(exactTimes.keySet());
            Collections.sort(finalList, (left, right) -> Long.compare(exactTimes.get(right), exactTimes.get(left)));

            PackageManager pm = activity.getPackageManager();
            SharedPreferences dbNames = activity.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
            File dbIconsDir = new File(activity.getFilesDir(), "saved_app_icons");
            final List<AppData> preloadedData = new ArrayList<>();

            // 1. Собираем тяжелые иконки и названия в ФОНЕ!
            int fetchLimit = 0;
            for (String pkg : finalList) {
                if (fetchLimit >= 10) break;
                
                AppData data = new AppData();
                data.pkgName = pkg;
                data.time = exactTimes.get(pkg);
                data.isDeleted = false;
                
                ApplicationInfo appInfo = null;

                try {
                    appInfo = pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    try {
                        int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                                   PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                        appInfo = pm.getApplicationInfo(pkg, flag);
                        
                        // СПАСАЕМ СИСТЕМНЫЕ ПРИЛОЖЕНИЯ: Проверяем, скрыто ли оно оболочкой
                        boolean isInstalled = (appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                        boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        
                        if (!isInstalled && !isSystemApp) {
                            data.isDeleted = true; // Физически удалено пользователем
                        } else {
                            data.isDeleted = false; // Просто отключенный/скрытый системный процесс
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                        data.isDeleted = true; // Система вообще не знает этот пакет - 100% удален
                    }
                }

                // ДОСТАЕМ ИМЯ (Из Сейфа или системы)
                String cachedName = dbNames.getString(pkg, null);
                if (cachedName != null) {
                    data.appName = cachedName;
                } else if (appInfo != null) {
                    data.appName = pm.getApplicationLabel(appInfo).toString();
                } else {
                    data.appName = formatDeletedAppName(pkg);
                }

                // ДОСТАЕМ ИКОНКУ (Из Сейфа или системы)
                File diskIcon = new File(dbIconsDir, pkg + ".png");
                if (diskIcon.exists()) {
                    data.icon = android.graphics.drawable.Drawable.createFromPath(diskIcon.getAbsolutePath());
                } else if (appInfo != null) {
                    try {
                        data.icon = pm.getApplicationIcon(appInfo);
                    } catch (Exception ignored) {}
                }
                
                preloadedData.add(data);
                fetchLimit++;
            }

            // 2. Возвращаемся в UI-поток только для мгновенной сборки карточек
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing() || appsContainer == null) return;

                long minutes = finalTotalMillis / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;
                
                if (weekTimeText != null) {
                    String timeStr = (hours > 0) 
                        ? activity.getString(R.string.format_hours_mins, hours, mins) 
                        : activity.getString(R.string.format_mins, mins);
                    weekTimeText.setText(timeStr);
                }
                
                int uiLimit = 0;
                for (AppData data : preloadedData) {
                    View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, appsContainer, false);
                    
                    if (uiLimit >= 2) {
                        view.setVisibility(View.GONE);
                    }
                    
                    ImageView iconView = view.findViewById(R.id.app_icon);
                    TextView nameView = view.findViewById(R.id.app_name);
                    TextView timeView = view.findViewById(R.id.app_time);
                    ImageView iconDeleted = view.findViewById(R.id.icon_deleted);
                    
                    nameView.setText(data.appName);
                    if (data.icon != null) {
                        iconView.setImageDrawable(data.icon);
                    } else {
                        iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                    }
                    timeView.setText(Utils.formatTime(activity, data.time));
                    
                    // Логика корзины
                    if (iconDeleted != null) {
                        if (data.isDeleted) {
                            iconDeleted.setVisibility(View.VISIBLE);
                            iconDeleted.setOnClickListener(v -> Toast.makeText(activity, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show());
                        } else {
                            iconDeleted.setVisibility(View.GONE);
                            iconDeleted.setOnClickListener(null);
                        }
                    }
                    
                    appsContainer.addView(view);

                    // Пробрасываем события для владельца (скрытие/описание)
                    if (activity.getSupportFragmentManager() != null) {
                        for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                            if (f instanceof ProfileFragment) {
                                ((ProfileFragment) f).setupOwnerAppInteractions(activity, view, data.pkgName);
                                break;
                            }
                        }
                    }
                    uiLimit++;
                }
                
                View btnExpand = ((View)appsContainer.getParent()).findViewById(R.id.btn_expand_apps);
                if (btnExpand != null) {
                    btnExpand.setVisibility(uiLimit > 2 ? View.VISIBLE : View.GONE);
                }
            });
        });
    }
}
