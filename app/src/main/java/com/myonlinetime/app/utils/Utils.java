package com.myonlinetime.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Utils {

    // =========================================================================
    // ГЛОБАЛЬНЫЙ ПУЛ ПОТОКОВ ДЛЯ ТЯЖЕЛОЙ МАТЕМАТИКИ
    // Создает до 4 потоков, которые автоматически работают с низким приоритетом,
    // чтобы не душить отрисовку интерфейса (Главный поток) при скролле и свайпах.
    // =========================================================================
    public static final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(() -> {
            // Магия плавности: отдаем приоритет интерфейсу
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        });
        // Делаем потоки фоновыми (демонами), чтобы они умирали вместе с приложением
        thread.setDaemon(true); 
        return thread;
    });

    // 1. Делаем картинку круглой
    // ... 

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
