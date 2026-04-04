package com.myonlinetime.app.adapters;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;
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
    
    // Кэш иконок (ограничен по количеству, так как картинки жрут память)
    private final LruCache<String, Drawable> iconCache;
    
    // Кэш названий (обычная мапа, так как строки почти не весят, храним их вечно)
    private final HashMap<String, String> nameCache;

    public AppsAdapter(Context context, int itemLayoutId, boolean isLimitEnabled) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
        this.isLimitEnabled = isLimitEnabled;
        this.visibleLimit = isLimitEnabled ? 3 : -1;

        // Потоки для тяжелых иконок
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Увеличили кэш до 150 иконок, чтобы при развертывании списка они не выпадали из памяти
        this.iconCache = new LruCache<>(150);
        this.nameCache = new HashMap<>();
    }

    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<>();
        this.visibleLimit = isLimitEnabled ? 3 : this.packageNames.size();
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
        visibleLimit = Math.min(packageNames.size(), visibleLimit + 15);
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        
        return isFullyExpanded(); 
    }

    public void collapse() {
        if (!isLimitEnabled || visibleLimit <= 3) return;
        int oldLimit = visibleLimit;
        visibleLimit = 3;
        hasStartedExpanding = false;
        notifyItemRangeRemoved(3, oldLimit - 3);
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(itemLayoutId, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        String pkg = packageNames.get(position);
        Long exactTime = exactTimes.get(pkg);
        holder.timeView.setText(Utils.formatTime(context, exactTime != null ? exactTime : 0L));

        holder.currentPkg = pkg;
        holder.iconView.setImageDrawable(null); // Сбрасываем старую картинку сразу

        // =======================================================
        // ШАГ 1: НАЗВАНИЕ (Грузим мгновенно в главном потоке)
        // =======================================================
        String cachedName = nameCache.get(pkg);
        ApplicationInfo appInfo = null;

        if (cachedName != null) {
            holder.nameView.setText(cachedName);
        } else {
            try {
                appInfo = pm.getApplicationInfo(pkg, 0);
                cachedName = pm.getApplicationLabel(appInfo).toString();
                nameCache.put(pkg, cachedName);
                holder.nameView.setText(cachedName);
            } catch (Exception e) {
                holder.nameView.setText(pkg);
            }
        }

        // =======================================================
        // ШАГ 2: ИКОНКА (Грузим асинхронно, чтобы не убить скролл)
        // =======================================================
        Drawable cachedIcon = iconCache.get(pkg);
        
        if (cachedIcon != null) {
            holder.iconView.setImageDrawable(cachedIcon);
        } else {
            // Передаем appInfo, чтобы не искать его в системе второй раз
            final ApplicationInfo finalAppInfo = appInfo; 
            
            executorService.execute(() -> {
                try {
                    // Если appInfo не нашли на ШАГЕ 1, ищем сейчас
                    ApplicationInfo infoToUse = finalAppInfo != null ? finalAppInfo : pm.getApplicationInfo(pkg, 0);
                    Drawable appIcon = pm.getApplicationIcon(infoToUse);
                    iconCache.put(pkg, appIcon);

                    mainHandler.post(() -> {
                        if (pkg.equals(holder.currentPkg)) {
                            holder.iconView.setImageDrawable(appIcon);
                        }
                    });
                } catch (Exception ignored) {
                    mainHandler.post(() -> {
                        if (pkg.equals(holder.currentPkg)) {
                            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                        }
                    });
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        if (!isLimitEnabled) return packageNames.size();
        return Math.min(visibleLimit, packageNames.size());
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView iconView; TextView nameView; TextView timeView;
        String currentPkg;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            timeView = itemView.findViewById(R.id.app_time);
        }
    }
}
