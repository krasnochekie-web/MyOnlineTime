package com.myonlinetime.app.utils;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private static class AppData {
        String pkgName;
        String appName;
        android.graphics.drawable.Drawable icon;
        long time;
        boolean isDeleted;
    }

    private static String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1];
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        } catch (Exception e) {
            return pkg;
        }
    }

    // === Растеризация иконки в Bitmap нужного размера (вызывается в фоне) ===
    // Adaptive-иконки дорого перерисовывать каждый кадр — переводим их в готовый
    // BitmapDrawable один раз, чтобы главный поток только блитил готовый bitmap.
    private static android.graphics.drawable.Drawable rasterizeIcon(
            Context ctx, android.graphics.drawable.Drawable src, int sizePx) {
        if (src == null || sizePx <= 0) return src;
        try {
            // Если это уже небольшой bitmap — не пересоздаём.
            if (src instanceof BitmapDrawable) {
                Bitmap bm = ((BitmapDrawable) src).getBitmap();
                if (bm != null && bm.getWidth() <= sizePx * 1.5f && bm.getHeight() <= sizePx * 1.5f) {
                    return src;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            src.setBounds(0, 0, sizePx, sizePx);
            src.draw(canvas);
            return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable t) {
            return src;
        }
    }

    // === ГЛОБАЛЬНАЯ ЛОГИКА СВОРАЧИВАНИЯ (Исправлено для 2 и 3 приложений) ===
    public static void applyCollapseLogic(TextView aboutView, LinearLayout container, ImageView btnExpand, ImageView btnCollapse) {
        if (container == null || aboutView == null || btnExpand == null || btnCollapse == null) return;

        boolean isEmptyDesc = aboutView.getText().toString().trim().isEmpty() || aboutView.getText().toString().equals("...");
        aboutView.setVisibility(isEmptyDesc ? View.GONE : View.VISIBLE);

        if (aboutView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) aboutView.getParent();
            if (parent.getBackground() != null || parent.getChildCount() == 1) {
                parent.setVisibility(isEmptyDesc ? View.GONE : View.VISIBLE);
            }
        }

        int limit = isEmptyDesc ? 3 : 2;
        int count = container.getChildCount();

        if (count <= limit) {
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.GONE);
            for (int i = 0; i < count; i++) {
                container.getChildAt(i).setVisibility(View.VISIBLE);
            }
        } else {
            if (btnCollapse.getVisibility() == View.VISIBLE) {
                btnExpand.setVisibility(View.GONE);
                for (int i = 0; i < count; i++) {
                    container.getChildAt(i).setVisibility(View.VISIBLE);
                }
            } else {
                btnExpand.setVisibility(View.VISIBLE);
                for (int i = 0; i < count; i++) {
                    container.getChildAt(i).setVisibility(i < limit ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

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

    public static void loadStatsToProfile(final MainActivity activity, final TextView weekTimeText, final LinearLayout appsContainer) {
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

            // Целевой размер иконки (48dp) — растеризуем под него один раз в фоне.
            final int iconPx = (int) (48 * activity.getResources().getDisplayMetrics().density);

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

                        boolean isInstalled = (appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                        boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                        if (!isInstalled && !isSystemApp) {
                            data.isDeleted = true;
                        } else {
                            data.isDeleted = false;
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                        data.isDeleted = true;
                    }
                }

                String cachedName = dbNames.getString(pkg, null);
                if (cachedName != null) {
                    data.appName = cachedName;
                } else if (appInfo != null) {
                    data.appName = pm.getApplicationLabel(appInfo).toString();
                } else {
                    data.appName = formatDeletedAppName(pkg);
                }

                File diskIcon = new File(dbIconsDir, pkg + ".png");
                if (diskIcon.exists()) {
                    data.icon = android.graphics.drawable.Drawable.createFromPath(diskIcon.getAbsolutePath());
                } else if (appInfo != null) {
                    try {
                        data.icon = pm.getApplicationIcon(appInfo);
                    } catch (Exception ignored) {}
                }

                // Растеризуем тяжёлую (adaptive) иконку в готовый bitmap в фоне,
                // чтобы главный поток не пересчитывал её каждый кадр.
                if (data.icon != null) {
                    data.icon = rasterizeIcon(activity, data.icon, iconPx);
                }

                preloadedData.add(data);
                fetchLimit++;
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing() || appsContainer == null) return;

                // === ИСПРАВЛЕНИЕ ДУБЛИКАТОВ ===
                appsContainer.setLayoutTransition(null);
                appsContainer.removeAllViews();

                long minutes = finalTotalMillis / 1000 / 60;
                long hours = minutes / 60;
                long mins = minutes % 60;

                if (weekTimeText != null) {
                    String timeStr = (hours > 0)
                            ? activity.getString(R.string.format_hours_mins, hours, mins)
                            : activity.getString(R.string.format_mins, mins);
                    weekTimeText.setText(timeStr);
                }

                // Ищем ProfileFragment ОДИН раз, а не на каждое приложение.
                ProfileFragment ownerFragment = null;
                if (activity.getSupportFragmentManager() != null) {
                    for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                        if (f instanceof ProfileFragment) {
                            ownerFragment = (ProfileFragment) f;
                            break;
                        }
                    }
                }

                // Добавляем вьюхи батчами по 3 за кадр — список дорисовывается
                // плавно, без одного длинного «фризящего» кадра.
                addAppViewsBatched(activity, appsContainer, preloadedData, 0, 3, ownerFragment);
            });
        });
    }

    // === Добавление элементов списка батчами, чтобы не фризить главный поток ===
    private static void addAppViewsBatched(final MainActivity activity,
                                           final LinearLayout appsContainer,
                                           final List<AppData> data,
                                           final int startIndex,
                                           final int batchSize,
                                           final ProfileFragment ownerFragment) {
        if (activity.isDestroyed() || activity.isFinishing() || appsContainer == null) return;

        int end = Math.min(startIndex + batchSize, data.size());

        for (int i = startIndex; i < end; i++) {
            AppData appData = data.get(i);

            View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, appsContainer, false);

            ImageView iconView = view.findViewById(R.id.app_icon);
            TextView nameView = view.findViewById(R.id.app_name);
            TextView timeView = view.findViewById(R.id.app_time);
            ImageView iconDeleted = view.findViewById(R.id.icon_deleted);

            nameView.setText(appData.appName);
            if (appData.icon != null) {
                iconView.setImageDrawable(appData.icon);
            } else {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            timeView.setText(Utils.formatTime(activity, appData.time));

            if (iconDeleted != null) {
                if (appData.isDeleted) {
                    iconDeleted.setVisibility(View.VISIBLE);
                    iconDeleted.setOnClickListener(v -> Toast.makeText(activity, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show());
                } else {
                    iconDeleted.setVisibility(View.GONE);
                    iconDeleted.setOnClickListener(null);
                }
            }

            appsContainer.addView(view);

            if (ownerFragment != null) {
                ownerFragment.setupOwnerAppInteractions(activity, view, appData.pkgName);
            }
        }

        if (end < data.size()) {
            // Следующий батч — на следующий кадр.
            appsContainer.post(() -> addAppViewsBatched(activity, appsContainer, data, end, batchSize, ownerFragment));
        } else {
            // === ПРИМЕНЕНИЕ ГЛОБАЛЬНОЙ ЛОГИКИ СВОРАЧИВАНИЯ ===
            TextView aboutView = activity.findViewById(R.id.profile_about);
            ImageView btnExpand = activity.findViewById(R.id.btn_expand_apps);
            ImageView btnCollapse = activity.findViewById(R.id.btn_collapse_apps);
            applyCollapseLogic(aboutView, appsContainer, btnExpand, btnCollapse);
        }
    }
}
