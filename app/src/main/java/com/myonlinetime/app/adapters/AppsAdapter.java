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

import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;

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

        // Инициализируем нашу вечную базу данных
        this.dbNames = context.getSharedPreferences("MyOnlineTime_AppNamesDB", Context.MODE_PRIVATE);
        this.dbIconsDir = new File(context.getFilesDir(), "saved_app_icons");
        if (!this.dbIconsDir.exists()) {
            this.dbIconsDir.mkdirs();
        }
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

    // Сохранение иконки в файл
    private void saveIconToDisk(Drawable drawable, String pkgName) {
        try {
            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            File file = new File(dbIconsDir, pkgName + ".png");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
        } catch (Exception ignored) { }
    }

    // Умный парсер для совсем уж мертвых пакетов
    private String formatDeletedAppName(String pkg) {
        try {
            String[] parts = pkg.split("\\.");
            String name = parts[parts.length - 1]; 
            return name.substring(0, 1).toUpperCase() + name.substring(1); 
        } catch (Exception e) {
            return pkg;
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
        holder.iconView.setImageDrawable(null); 

        // 1. ПРОВЕРЯЕМ, УДАЛЕНО ЛИ ПРИЛОЖЕНИЕ (Моментальная проверка)
        boolean isDeleted = false;
        ApplicationInfo activeAppInfo = null;
        try {
            activeAppInfo = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            isDeleted = true;
        }

        // Показываем или прячем корзину
        if (isDeleted && holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.VISIBLE);
            holder.iconDeleted.setOnClickListener(v -> 
                Toast.makeText(context, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show()
            );
        } else if (holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.GONE);
            holder.iconDeleted.setOnClickListener(null);
        }

        // 2. ДОСТАЕМ ИМЯ (RAM -> Диск -> Система)
        String finalName = nameCache.get(pkg);
        if (finalName != null) {
            holder.nameView.setText(finalName);
        } else {
            finalName = dbNames.getString(pkg, null);
            if (finalName != null) {
                nameCache.put(pkg, finalName);
                holder.nameView.setText(finalName);
            } else if (activeAppInfo != null) {
                // Впервые видим живое приложение! Сохраняем навсегда в базу
                finalName = pm.getApplicationLabel(activeAppInfo).toString();
                dbNames.edit().putString(pkg, finalName).apply();
                nameCache.put(pkg, finalName);
                holder.nameView.setText(finalName);
            } else {
                // Приложение уже удалено, и в базе его нет. Запускаем парсер.
                holder.nameView.setText(formatDeletedAppName(pkg));
            }
        }

        // 3. ДОСТАЕМ ИКОНКУ (RAM -> Диск -> Система)
        Drawable cachedIcon = iconCache.get(pkg);
        if (cachedIcon != null) {
            holder.iconView.setImageDrawable(cachedIcon);
        } else {
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            final ApplicationInfo finalAppInfo = activeAppInfo; 
            
            executorService.execute(() -> {
                try {
                    Drawable appIcon = null;
                    File diskIcon = new File(dbIconsDir, pkg + ".png");
                    
                    // Ищем на диске
                    if (diskIcon.exists()) {
                        appIcon = Drawable.createFromPath(diskIcon.getAbsolutePath());
                    } 
                    // Если на диске нет, но приложение еще живое - достаем из системы и СОХРАНЯЕМ
                    else if (finalAppInfo != null) {
                        appIcon = pm.getApplicationIcon(finalAppInfo);
                        saveIconToDisk(appIcon, pkg);
                    }

                    if (appIcon != null) {
                        iconCache.put(pkg, appIcon);
                        final Drawable finalIconToSet = appIcon;
                        mainHandler.post(() -> {
                            if (pkg.equals(holder.currentPkg)) {
                                holder.iconView.setImageDrawable(finalIconToSet);
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
