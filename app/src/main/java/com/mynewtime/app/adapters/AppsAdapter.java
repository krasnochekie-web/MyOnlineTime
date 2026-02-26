package com.mynewtime.app.adapters;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

import com.mynewtime.app.R;
import com.mynewtime.app.utils.Utils;

public class AppsAdapter extends BaseAdapter {

    private Context context;
    private List<String> list; // У тебя называется list
    private PackageManager pm;
    private Map<String, Long> exactTimes;

    public AppsAdapter(Context context, List<String> list, Map<String, Long> exactTimes) {
        this.context = context; 
        this.list = list; 
        this.pm = context.getPackageManager();
        this.exactTimes = exactTimes;
    }

    // --- НАШ НОВЫЙ МЕТОД ДЛЯ ПЛАВНОГО ОБНОВЛЕНИЯ ---
    public void updateData(List<String> newList, Map<String, Long> newExactTimes) {
        if (this.list != null) {
            this.list.clear();
            this.list.addAll(newList);
        }
        if (this.exactTimes != null) {
            this.exactTimes.clear();
            this.exactTimes.putAll(newExactTimes);
        }
        notifyDataSetChanged(); // Говорим списку перерисоваться мягко
    }
    // ------------------------------------------------

    @Override public int getCount() { return list.size(); }

    @Override public Object getItem(int position) { return list.get(position); }

    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_app_usage, parent, false);
        }
        
        String pkg = list.get(position);
        
        ImageView iconView = convertView.findViewById(R.id.app_icon);
        TextView nameView = convertView.findViewById(R.id.app_name);
        TextView timeView = convertView.findViewById(R.id.app_time);
        
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            nameView.setText(pm.getApplicationLabel(appInfo));
            iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (Exception e) { 
            nameView.setText(pkg); 
        }
        
        Long exactTime = exactTimes.get(pkg);
        timeView.setText(Utils.formatTime(timeView.getContext(), exactTime != null ? exactTime : 0L));
        
        return convertView;
    }
}