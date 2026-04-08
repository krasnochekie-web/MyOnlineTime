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
    private static final LruCache<String, Drawable> iconCache = new LruCache<>(150);
    // Кэш названий (обычная мапа, так как строки почти не весят, храним их вечно)
    private static final HashMap<String, String> nameCache = new HashMap<>();

    public AppsAdapter(Context context, int itemLayoutId, boolean isLimitEnabled) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
        this.isLimitEnabled = isLimitEnabled;
        
        // Потоки для тяжелых иконок
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<>();
        
        // Стартуем с 5 элементов для плавности анимации перехода
        this.visibleLimit = isLimitEnabled ? Math.min(5, this.packageNames.size()) : this.packageNames.size();
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
        // Грузим пачками по 20 штук
        visibleLimit = Math.min(packageNames.size(), visibleLimit + 20);
        
        // Плавная вставка элементов в список
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        
        return isFullyExpanded(); 
    }

    public void collapse() {
        if (!isLimitEnabled || visibleLimit <= 5) return;
        int oldLimit = visibleLimit;
        visibleLimit = Math.min(5, packageNames.size());
        hasStartedExpanding = false;
        
        // Плавное удаление элементов из списка
        notifyItemRangeRemoved(visibleLimit, oldLimit - visibleLimit);
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(itemLayoutId, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        // === МАГИЯ ФОНОВ (Lego-метод) ===
        if (position == 0) {
            // Первая ячейка получает скругленные ВЕРХНИЕ углы
            holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_top);
        } else {
            // Все остальные ячейки квадратные, чтобы слиться в монолит
            holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_mid);
        }

        // Убираем вертикальные отступы, чтобы ячейки плотно склеились
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        if (params != null) {
            params.bottomMargin = 0;
            params.topMargin = 0;
            holder.itemView.setLayoutParams(params);
        }
        // ================================

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
            // Временная заглушка пока идет поиск файла
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            
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
                    // Если иконки нет, оставляем дефолтную (уже установлена)
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        if (!isLimitEnabled) return packageNames.size();
        return visibleLimit;
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
