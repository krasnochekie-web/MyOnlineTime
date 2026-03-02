package com.mynewtime.app.adapters;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mynewtime.app.R;
import com.mynewtime.app.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Наследуемся от мощного RecyclerView
public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {
    private Context context;
    private List<String> packageNames = new ArrayList<>();
    private Map<String, Long> exactTimes = new HashMap<>();
    private PackageManager pm;
    
    private int itemLayoutId; // НОВАЯ ПЕРЕМЕННАЯ ДЛЯ ДИЗАЙНА

    // Теперь адаптер требует передать ему файл дизайна при создании
    public AppsAdapter(Context context, int itemLayoutId) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
    }

    // Метод для обновления данных (вызывается из StatsFragment)
    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<String>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<String, Long>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Заменили R.layout.item_app_usage на нашу переменную itemLayoutId
        View view = LayoutInflater.from(context).inflate(itemLayoutId, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        String pkg = packageNames.get(position);
        Long exactTime = exactTimes.get(pkg);
        
        // Форматируем время (с учетом нашего нового метода с контекстом!)
        holder.timeView.setText(Utils.formatTime(context, exactTime != null ? exactTime : 0L));

        // Пытаемся получить иконку и нормальное имя приложения
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            holder.nameView.setText(pm.getApplicationLabel(appInfo));
            holder.iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (Exception e) {
            holder.nameView.setText(pkg); // Если не вышло, показываем имя пакета
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    @Override
    public int getItemCount() {
        return packageNames.size();
    }

    // Тот самый холдер ссылок
    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView iconView;
        TextView nameView;
        TextView timeView;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            timeView = itemView.findViewById(R.id.app_time);
        }
    }
}
