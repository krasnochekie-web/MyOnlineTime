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

    // =========================================================================
    // >>> ИНСТРУМЕНТЫ ОПТИМИЗАЦИИ <<<
    // =========================================================================
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final LruCache<String, Drawable> iconCache;
    private final LruCache<String, String> nameCache;

    public AppsAdapter(Context context, int itemLayoutId, boolean isLimitEnabled) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
        this.isLimitEnabled = isLimitEnabled;
        this.visibleLimit = isLimitEnabled ? 3 : -1;

        // Инициализируем 4 фоновых потока для быстрой загрузки
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Выделяем память под кэш (храним до 100 иконок и названий)
        int cacheSize = 100;
        this.iconCache = new LruCache<>(cacheSize);
        this.nameCache = new LruCache<>(cacheSize);
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

    // Загружает еще 15 элементов плавно
    public boolean loadMoreChunk() {
        if (isFullyExpanded()) return true;
        hasStartedExpanding = true;
        
        int oldLimit = visibleLimit;
        visibleLimit = Math.min(packageNames.size(), visibleLimit + 15);
        notifyItemRangeInserted(oldLimit, visibleLimit - oldLimit);
        
        return isFullyExpanded(); 
    }

    // Сворачивает обратно в 3 элемента
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

        // Привязываем текущий пакет к холдеру (важно для асинхронной проверки)
        holder.currentPkg = pkg;

        // 1. Очищаем холдер (сбрасываем старую картинку, так как при скролле ячейки переиспользуются)
        holder.iconView.setImageDrawable(null);
        holder.nameView.setText(pkg); // Временно показываем системный пакет, пока грузится красивое имя

        // 2. Ищем данные в кэше
        Drawable cachedIcon = iconCache.get(pkg);
        String cachedName = nameCache.get(pkg);

        if (cachedIcon != null && cachedName != null) {
            // БИНГО! Данные есть в памяти. Отрисовываем мгновенно без нагрузки на систему
            holder.iconView.setImageDrawable(cachedIcon);
            holder.nameView.setText(cachedName);
        } else {
            // 3. Данных нет. Запрашиваем у системы в ФОНОВОМ потоке (чтобы не лагал скролл)
            executorService.execute(() -> {
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                    String appName = pm.getApplicationLabel(appInfo).toString();
                    Drawable appIcon = pm.getApplicationIcon(appInfo);

                    // Сохраняем в память
                    nameCache.put(pkg, appName);
                    iconCache.put(pkg, appIcon);

                    // 4. Возвращаемся в главный поток, чтобы показать результат
                    mainHandler.post(() -> {
                        // КРИТИЧЕСКАЯ ПРОВЕРКА: пока мы искали иконку, пользователь мог проскроллить список,
                        // и этот холдер уже отдали другому приложению. Проверяем, совпадает ли пакет.
                        if (pkg.equals(holder.currentPkg)) {
                            holder.nameView.setText(appName);
                            holder.iconView.setImageDrawable(appIcon);
                        }
                    });
                } catch (Exception e) {
                    // Если приложение удалено или не найдено
                    mainHandler.post(() -> {
                        if (pkg.equals(holder.currentPkg)) {
                            holder.nameView.setText(pkg); 
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
        String currentPkg; // Поле для хранения текущего пакета ячейки

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            timeView = itemView.findViewById(R.id.app_time);
        }
    }
}
