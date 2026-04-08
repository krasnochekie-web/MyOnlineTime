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
        
        // ИСПРАВЛЕНО: Вернули ваши законные 3 элемента
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
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        
        return isFullyExpanded(); 
    }

    public void collapse() {
        if (!isLimitEnabled || visibleLimit <= 3) return;
        int oldLimit = visibleLimit;
        // ИСПРАВЛЕНО: Сворачиваем до 3 элементов
        visibleLimit = Math.min(3, packageNames.size());
        hasStartedExpanding = false;
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
        // Проверяем, будет ли под списком отображаться подвал "Показать еще"
        boolean hasFooter = (packageNames.size() > 3) && (!hasStartedExpanding || isFullyExpanded());

        // === ИСПРАВЛЕННАЯ МАГИЯ ФОНОВ И ОТСТУПОВ ===
        if (currentTotalVisible == 1 && !hasFooter) {
            // Багфикс: Если элемент всего 1, он должен быть закруглен со всех сторон
            holder.itemView.setBackgroundResource(R.drawable.bg_app_card);
        } else if (position == 0) {
            holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_top);
        } else if (position == currentTotalVisible - 1 && !hasFooter) {
            // Багфикс: Последний элемент без подвала должен закруглять низ
            holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_bot);
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_card_stack_mid);
        }

        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        if (params != null) {
            params.topMargin = 0;
            if (!hasFooter && position == currentTotalVisible - 1) {
                // Багфикс: Если подвала нет, возвращаем 16dp отступа, чтобы не наезжать на карточки
                params.bottomMargin = (int) (16 * context.getResources().getDisplayMetrics().density);
            } else {
                params.bottomMargin = 0;
            }
            holder.itemView.setLayoutParams(params);
        }
        // ============================================

        String pkg = packageNames.get(position);
        Long exactTime = exactTimes.get(pkg);
        holder.timeView.setText(Utils.formatTime(context, exactTime != null ? exactTime : 0L));

        holder.currentPkg = pkg;
        holder.iconView.setImageDrawable(null); 

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

        Drawable cachedIcon = iconCache.get(pkg);
        
        if (cachedIcon != null) {
            holder.iconView.setImageDrawable(cachedIcon);
        } else {
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            final ApplicationInfo finalAppInfo = appInfo; 
            
            executorService.execute(() -> {
                try {
                    ApplicationInfo infoToUse = finalAppInfo != null ? finalAppInfo : pm.getApplicationInfo(pkg, 0);
                    Drawable appIcon = pm.getApplicationIcon(infoToUse);
                    iconCache.put(pkg, appIcon);

                    mainHandler.post(() -> {
                        if (pkg.equals(holder.currentPkg)) {
                            holder.iconView.setImageDrawable(appIcon);
                        }
                    });
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
