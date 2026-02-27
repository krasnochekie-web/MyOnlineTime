package com.mynewtime.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

public class Utils {

    // 1. Делаем картинку круглой

    // 2. Форматируем время (часы и минуты)
    // 2. Форматируем время (передаем context для получения строк локализации)
    public static String formatTime(android.content.Context context, long millis) {
        String strM = context.getString(com.mynewtime.app.R.string.unit_m);
        String strH = context.getString(com.mynewtime.app.R.string.unit_h);
        
        if (millis <= 0) return "0 " + strM;
        long minutes = millis / 1000 / 60;
        long hours = minutes / 60;
        long mins = minutes % 60;
        
        if (hours > 0) return hours + " " + strH + " " + mins + " " + strM;
        return mins + " " + strM;
    }

    // --- 3. РИСУЕМ КАРТОЧКУ ПОЛЬЗОВАТЕЛЯ ---
    public static android.view.View createSearchUserCard(final com.mynewtime.app.MainActivity activity, final com.mynewtime.app.models.User u) {
        android.view.View card = android.view.LayoutInflater.from(activity).inflate(com.mynewtime.app.R.layout.item_search_user, null, false);
        android.widget.ImageView img = card.findViewById(com.mynewtime.app.R.id.search_user_avatar);
        android.widget.TextView name = card.findViewById(com.mynewtime.app.R.id.search_user_name);
        
        name.setText(u.nickname != null ? u.nickname : activity.getString(com.mynewtime.app.R.string.new_user));
        
        if (u.photo != null && u.photo.length() > 10) {
            activity.loadBase64ImageAsync(img, u.photo);
        }
        card.setOnClickListener(new android.view.View.OnClickListener() { 
            public void onClick(android.view.View v) { 
                activity.navigator.switchScreen(4, u.id);
            }
        });
        
        return card;
    }
}