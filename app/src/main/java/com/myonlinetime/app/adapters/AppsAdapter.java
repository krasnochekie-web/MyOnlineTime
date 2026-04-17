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
import android.widget.Toast;

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
    
    private static final LruCache<String, Drawable> iconCache = new LruCache<>(150);
    private static final HashMap<String, String> nameCache = new HashMap<>();

    public AppsAdapter(Context context, int itemLayoutId, boolean isLimitEnabled) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
        this.isLimitEnabled = isLimitEnabled;
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<>();
        
        // Лимит в 3 элемента только если isLimitEnabled = true (главный экран)
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
        
        // Убираем шов у старой последней ячейки
        if (oldLimit > 0) notifyItemChanged(oldLimit - 1);
        
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        return isFullyExpanded(); 
    }

    public void collapse() {
        if (!isLimitEnabled || visibleLimit <= 3) return;
        int oldLimit = visibleLimit;
        visibleLimit = Math.min(3, packageNames.size());
        hasStartedExpanding = false;
        
        // Возвращаем закругление новой последней ячейке
        if (visibleLimit > 0) notifyItemChanged(visibleLimit - 1);
        
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
        int currentTotalVisible = getItemCount();
        float density = context.getResources().getDisplayMetrics().density;

        // === ГИБКАЯ МАГИЯ ФОНОВ И ОТСТУПОВ ===
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        
        if (isLimitEnabled) {
            // РЕЖИМ LEGO (Главный экран)
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
            // РЕЖИМ КРАСИВОЙ КАРТОЧКИ (Графики / За всё время)
            holder.itemView.setBackgroundResource(R.drawable.bg_app_card);
            if (params != null) {
                params.leftMargin = (int) (16 * density);
                params.rightMargin = (int) (16 * density);
                params.topMargin = 0;
                params.bottomMargin = (int) (8 * density);
            }
        }
        if (params != null) holder.itemView.setLayoutParams(params);
        // ============================================

        String pkg = packageNames.get(position);
        Long exactTime = exactTimes.get(pkg);
        holder.timeView.setText(Utils.formatTime(context, exactTime != null ? exactTime : 0L));

        holder.currentPkg = pkg;
        holder.iconView.setImageDrawable(null); 

        // =====================================================================
        // УМНЫЙ ПОИСК ДАННЫХ ПРИЛОЖЕНИЯ (ВКЛЮЧАЯ УДАЛЕННЫЕ)
        // =====================================================================
        ApplicationInfo appInfo = null;
        boolean isDeleted = false;

        try {
            // Сначала ищем как обычно
            appInfo = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            // Если не нашли - значит оно удалено! Достаем из системного кэша призраков
            isDeleted = true;
            try {
                int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ? 
                           PackageManager.MATCH_UNINSTALLED_PACKAGES : PackageManager.GET_UNINSTALLED_PACKAGES;
                appInfo = pm.getApplicationInfo(pkg, flag);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        // Включаем/отключаем корзину
        if (isDeleted && holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.VISIBLE);
            holder.iconDeleted.setOnClickListener(v -> 
                Toast.makeText(context, R.string.toast_app_deleted, Toast.LENGTH_SHORT).show()
            );
        } else if (holder.iconDeleted != null) {
            holder.iconDeleted.setVisibility(View.GONE);
            holder.iconDeleted.setOnClickListener(null); // Снимаем клик, чтобы не мешал
        }

        // === РАБОТА С НАЗВАНИЕМ ===
        String cachedName = nameCache.get(pkg);
        if (cachedName != null) {
            holder.nameView.setText(cachedName);
        } else {
            if (appInfo != null) {
                cachedName = pm.getApplicationLabel(appInfo).toString();
                nameCache.put(pkg, cachedName);
                holder.nameView.setText(cachedName);
            } else {
                // Если система стерла даже кэш удаленного приложения, показываем имя пакета (напр. com.whatsapp)
                holder.nameView.setText(pkg);
            }
        }

        // === РАБОТА С ИКОНКОЙ ===
        Drawable cachedIcon = iconCache.get(pkg);
        if (cachedIcon != null) {
            holder.iconView.setImageDrawable(cachedIcon);
        } else {
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            final ApplicationInfo finalAppInfo = appInfo; 
            
            // Если иконки нет в кэше, грузим её в фоне, чтобы список не тормозил
            executorService.execute(() -> {
                try {
                    if (finalAppInfo != null) {
                        Drawable appIcon = pm.getApplicationIcon(finalAppInfo);
                        iconCache.put(pkg, appIcon);
                        mainHandler.post(() -> {
                            // Проверяем, не прокрутил ли пользователь список, пока грузилась картинка
                            if (pkg.equals(holder.currentPkg)) {
                                holder.iconView.setImageDrawable(appIcon);
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
        ImageView iconDeleted; // Наша новая иконка корзины!
        String currentPkg;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            timeView = itemView.findViewById(R.id.app_time);
            iconDeleted = itemView.findViewById(R.id.icon_deleted); // Инициализируем корзину
        }
    }
}
