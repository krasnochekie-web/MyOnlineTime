package com.myonlinetime.app.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {
    private Context context;
    private List<String> packageNames = new ArrayList<>();
    private Map<String, Long> exactTimes = new HashMap<>();
    private PackageManager pm;
    
    private int itemLayoutId; 
    private boolean isLimitEnabled; 
    
    private int visibleLimit;
    private boolean hasStartedExpanding = false;

    // === НОВЫЙ ФЛАГ ДЛЯ ОПРЕДЕЛЕНИЯ ВЛАДЕЛЬЦА ПРОФИЛЯ ===
    private boolean isMyProfile = true; 

    private final ExecutorService executorService;
    private final Handler mainHandler;
    
    // Оперативная память (RAM)
    private static final LruCache<String, Drawable> iconCache = new LruCache<>(150);
    private static final HashMap<String, String> nameCache = new HashMap<>();

    // Жесткий диск (Надежная локальная база данных)
    private SharedPreferences dbNames;
    private File dbIconsDir;

    public AppsAdapter(Context context, int itemLayoutId, boolean isLimitEnabled) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
        this.isLimitEnabled = isLimitEnabled;
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());

        this.dbNames = context.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
        this.dbIconsDir = new File(context.getFilesDir(), "saved_app_icons");
        if (!this.dbIconsDir.exists()) {
            this.dbIconsDir.mkdirs();
        }
    }

    // Метод для переключения логики Корзины и Иконок (вызывай его при просмотре чужих профилей!)
    public void setIsMyProfile(boolean isMyProfile) {
        this.isMyProfile = isMyProfile;
    }

    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<>();
        
        this.visibleLimit = isLimitEnabled ? Math.min(3, this.packageNames.size()) : this.packageNames.size();
        this.hasStartedExpanding = false;
        notifyDataSetChanged();
    }

    public boolean isFullyExpanded() {
        return !isLimitEnabled || visibleLimit >= packageNames.size();
    }
    
    public boolean hasStartedExpanding() {
        return hasStartedExpanding;
    }

    public boolean loadMoreChunk() {
        if (isFullyExpanded()) return true;
        hasStartedExpanding = true;
        
        int oldLimit = visibleLimit;
        visibleLimit = Math.min(packageNames.size(), visibleLimit + 20);
        
        if (oldLimit > 0) notifyItemChanged(oldLimit - 1);
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        return isFullyExpanded(); 
    }

    public void collapse() {
        if (!isLimitEnabled || visibleLimit <= 3) return;
        int oldLimit = visibleLimit;
        visibleLimit = Math.min(3, packageNames.size());
        hasStartedExpanding = false;
        
        if (visibleLimit > 0) notifyItemChanged(visibleLimit - 1);
        notifyItemRangeRemoved(visibleLimit, oldLimit - visibleLimit);
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(itemLayoutId, parent, false);
        return new AppViewHolder(view);
    }

    private void saveIconToDisk(Drawable drawable, String pkgName) {
        try {
            Bitmap bitmap = drawableToBitmap(drawable);
            File file = new File(dbIconsDir, pkgName + ".png");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
        } catch (Exception ignored) { }
    }

    // === P2P МАГИЯ: Загружаем найденную иконку на сервер ===
    private void uploadIconToServerBackground(Drawable drawable, String pkgName) {
        try {
            if (context instanceof MainActivity) {
                String token = ((MainActivity) context).vpsToken;
                if (token != null) {
                    Bitmap bitmap = drawableToBitmap(drawable);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    // Сжимаем в PNG для идеального качества
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    byte[] iconBytes = baos.toByteArray();
                    
                    // Тихая фоновая отправка
                    VpsApi.uploadAppIcon(token, pkgName, iconBytes);
                }
            }
        } catch (Exception ignored) {}
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    // === УМНЫЙ СЛОВАРЬ (Фолбэк) ===
    private String getSmartAppName(String pkg) {
        if (pkg == null) return "Unknown";
        switch (pkg.toLowerCase()) {
            case "org.telegram.messenger": return "Telegram";
            case "com.whatsapp": return "WhatsApp";
            case "com.instagram.android": return "Instagram";
            case "com.zhiliaoapp.musically":
            case "com.ss.android.ugc.aweme": return "TikTok";
            case "com.google.android.youtube": return "YouTube";
            case "com.yandex.browser": return "Яндекс Браузер";
            case "com.android.chrome": return "Google Chrome";
            case "com.vkontakte.android": return "ВКонтакте";
            case "com.viber.voip": return "Viber";
            case "com.snapchat.android": return "Snapchat";
            case "com.spotify.android": return "Spotify";
            case "com.twitter.android": return "X (Twitter)";
            case "tv.twitch.android.app": return "Twitch";
            case "com.discord": return "Discord";
            case "com.skype.rover": return "Skype";
            case "com.facebook.katana":
            case "com.facebook.lite": return "Facebook";
            case "com.netflix.mediaclient": return "Netflix";
            case "ru.yandex.music": return "Яндекс Музыка";
            case "com.miHoYo.GenshinImpact": return "Genshin Impact";
            case "com.tencent.ig": return "PUBG Mobile";
            default:
                try {
                    String[] parts = pkg.split("\\.");
                    String name = parts[parts.length - 1]; 
                    if (name.equalsIgnoreCase("app") || name.equalsIgnoreCase("android") || name.equalsIgnoreCase("messenger") || name.equalsIgnoreCase("lite")) {
                        name = parts[parts.length - 2]; 
                    }
                    return name.substring(0, 1).toUpperCase() + name.substring(1); 
                } catch (Exception e) {
                    return pkg;
                }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        int currentTotalVisible = getItemCount();
        float density = context.getResources().getDisplayMetrics().density;

        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        
        if (isLimitEnabled) {
            boolean hasFooter = (packageNames.size() > 3) && (!hasStartedExpanding || isFullyExpanded());

            if (currentTotalVisible == 1 && !hasFooter) {
                holder.itemView.setBackgroundResource(R.drawable.bg_app_card);
            } else if (position == 0) {
                holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_top);
            } else if (position == currentTotalVisible - 1 && !hasFooter) {
                holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_bot);
            } else {
                holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_mid);
            }

            if (params != null) {
                params.topMargin = 0;
                params.leftMargin = (int) (16 * density);
                params.rightMargin = (int) (16 * density);
                if (!hasFooter && position == currentTotalVisible - 1) {
                    params.bottomMargin = (int) (16 * density);
                } else {
                    params.bottomMargin = 0;
                }
            }
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_app_card);
            if (params != null) {
                params.leftMargin = (int) (16 * density);
                params.rightMargin = (int) (16 * density);
                params.topMargin = 0;
                params.bottomMargin = (int) (8 * density);
            }
        }
        if (params != null) holder.itemView.setLayoutParams(params);

        String pkg = packageNames.get(position);
        Long exactTime = exactTimes.get(pkg);
        holder.timeView.setText(Utils.formatTime(context, exactTime != null ? exactTime : 0L));

        holder.currentPkg = pkg;
        
        // === ИСПРАВЛЕНИЕ: ЖЕСТКАЯ ОТМЕНА СТАРЫХ ЗАГРУЗОК GLIDE ===
        // Это предотвратит внезапное появление робота (ошибки старого запроса) поверх новой иконки
        Glide.with(context).clear(holder.iconView);
        holder.iconView.setImageDrawable(null); 

        // 1. УМНАЯ ПРОВЕРКА "КОРЗИНЫ" В ЗАВИСИМОСТИ ОТ ВЛАДЕЛЬЦА
        boolean isDeleted = false;
        ApplicationInfo activeAppInfo = null;

        if (isMyProfile) {
            // ЛОГИКА ДЛЯ ТВОЕГО ПРОФИЛЯ: Прямой запрос к системе телефона
            try {
                activeAppInfo = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                try {
                    int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                               PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                    activeAppInfo = pm.getApplicationInfo(pkg, flag);
                    
                    boolean isInstalled = (activeAppInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                    boolean isSystemApp = (activeAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    
                    if (!isInstalled && !isSystemApp) {
                        isDeleted = true; // Физически удалено тобой
                    } else {
                        isDeleted = false; // Скрыто оболочкой
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    isDeleted = true; // Система вообще не знает этот пакет
                }
            }
        } else {
            // ЛОГИКА ДЛЯ ЧУЖОГО ПРОФИЛЯ: Доверяем только времени с сервера!
            if (exactTime != null && exactTime == 0L) {
                isDeleted = true; // Пользователь удалил приложение у себя
            }
            // Всё равно пытаемся найти локальную инфу, чтобы красиво отрисовать имя
            try { activeAppInfo = pm.getApplicationInfo(pkg, 0); } 
            catch (PackageManager.NameNotFoundException ignored) {}
        }

        if (isDeleted && holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.VISIBLE);
            holder.iconDeleted.setOnClickListener(v -> 
                Toast.makeText(context, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show()
            );
        } else if (holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.GONE);
            holder.iconDeleted.setOnClickListener(null);
        }

        // 2. ДОСТАЕМ ИМЯ
        String finalName = nameCache.get(pkg);
        if (finalName != null) {
            holder.nameView.setText(finalName);
        } else {
            finalName = dbNames.getString(pkg, null);
            if (finalName != null) {
                nameCache.put(pkg, finalName);
                holder.nameView.setText(finalName);
            } else if (activeAppInfo != null) {
                finalName = pm.getApplicationLabel(activeAppInfo).toString();
                dbNames.edit().putString(pkg, finalName).apply();
                nameCache.put(pkg, finalName);
                holder.nameView.setText(finalName);
            } else {
                // Используем наш новый умный словарь вместо старого парсера
                holder.nameView.setText(getSmartAppName(pkg));
            }
        }

        // 3. ДОСТАЕМ ИКОНКУ (Локально ИЛИ через P2P сервер)
        Drawable cachedIcon = iconCache.get(pkg);
        if (cachedIcon != null) {
            holder.iconView.setImageDrawable(cachedIcon);
        } else {
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon); // Заглушка на время загрузки
            final ApplicationInfo finalAppInfo = activeAppInfo; 
            
            executorService.execute(() -> {
                try {
                    Drawable appIcon = null;
                    File diskIcon = new File(dbIconsDir, pkg + ".png");
                    
                    // А. Ищем на диске
                    if (diskIcon.exists()) {
                        appIcon = Drawable.createFromPath(diskIcon.getAbsolutePath());
                        // === ИСПРАВЛЕНИЕ: ПРИНУДИТЕЛЬНАЯ РАЗДАЧА КЭШИРОВАННЫХ ИКОНОК ===
                        uploadIconToServerBackground(appIcon, pkg);
                    } 
                    // Б. Если на диске нет, но приложение у нас есть - достаем из системы
                    else if (finalAppInfo != null) {
                        appIcon = pm.getApplicationIcon(finalAppInfo);
                        saveIconToDisk(appIcon, pkg);
                        
                        // Закидываем иконку на сервер для других пользователей! (P2P база)
                        uploadIconToServerBackground(appIcon, pkg);
                    }

                    // Если иконка нашлась ЛОКАЛЬНО
                    if (appIcon != null) {
                        iconCache.put(pkg, appIcon);
                        final Drawable finalIconToSet = appIcon;
                        mainHandler.post(() -> {
                            if (pkg.equals(holder.currentPkg)) {
                                holder.iconView.setImageDrawable(finalIconToSet);
                            }
                        });
                    } 
                    // В. Если приложения у нас НЕТ, тянем иконку с сервера (P2P раздача)
                    else {
                        mainHandler.post(() -> {
                            if (pkg.equals(holder.currentPkg)) {
                                String iconUrl = "https://api.krasnocraft.ru/icons/" + pkg + ".png";
                                Glide.with(context)
                                     .load(iconUrl)
                                     .placeholder(android.R.drawable.sym_def_app_icon)
                                     .error(android.R.drawable.sym_def_app_icon) // Безопасная системная заглушка
                                     .into(holder.iconView);
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    public int getItemCount() {
        if (!isLimitEnabled) return packageNames.size();
        return visibleLimit;
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView iconView; 
        TextView nameView; 
        TextView timeView;
        ImageView iconDeleted; 
        String currentPkg;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            timeView = itemView.findViewById(R.id.app_time);
            iconDeleted = itemView.findViewById(R.id.icon_deleted); 
        }
    }
}
