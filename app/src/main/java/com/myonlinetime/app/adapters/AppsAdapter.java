package com.myonlinetime.app.adapters;

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

import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {
    private Context context;
    private List<String> packageNames = new ArrayList<>();
    private Map<String, Long> exactTimes = new HashMap<>();
    private PackageManager pm;
    
    private int itemLayoutId; 
    private boolean isExpanded = false; // <-- ФЛАГ ДЛЯ КНОПКИ "ПОКАЗАТЬ БОЛЬШЕ"

    public AppsAdapter(Context context, int itemLayoutId) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.itemLayoutId = itemLayoutId; 
    }

    // Метод для переключения состояния списка
    public void setExpanded(boolean expanded) {
        this.isExpanded = expanded;
        notifyDataSetChanged();
    }

    public void updateData(List<String> newPackages, Map<String, Long> newTimes) {
        this.packageNames = newPackages != null ? newPackages : new ArrayList<String>();
        this.exactTimes = newTimes != null ? newTimes : new HashMap<String, Long>();
        notifyDataSetChanged();
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

        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            holder.nameView.setText(pm.getApplicationLabel(appInfo));
            holder.iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (Exception e) {
            holder.nameView.setText(pkg); 
            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    @Override
    public int getItemCount() {
        // <-- УМНЫЙ ЛИМИТ ЭЛЕМЕНТОВ -->
        if (isExpanded) {
            return packageNames.size(); // Показываем все
        } else {
            return Math.min(5, packageNames.size()); // Показываем только 5
        }
    }

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
