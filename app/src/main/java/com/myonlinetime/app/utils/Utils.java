package com.myonlinetime.app.utils;

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
        String strM = context.getString(com.myonlinetime.app.R.string.unit_m);
        String strH = context.getString(com.myonlinetime.app.R.string.unit_h);
        
        if (millis <= 0) return "0 " + strM;
        long minutes = millis / 1000 / 60;
        long hours = minutes / 60;
        long mins = minutes % 60;
        
        if (hours > 0) return hours + " " + strH + " " + mins + " " + strM;
        return mins + " " + strM;
    }
}