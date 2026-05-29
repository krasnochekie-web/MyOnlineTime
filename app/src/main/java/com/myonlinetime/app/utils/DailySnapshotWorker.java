package com.myonlinetime.app.utils;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;

// Лёгкий фоновый воркер: только фиксирует закрытые сутки в посуточный кэш,
// пока их события ещё живы в системе. Без уведомлений и сети.
public class DailySnapshotWorker extends Worker {

    public DailySnapshotWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            // Замораживаем последние 8 завершённых суток. Для однодневного окна
            // UsageMath сам перезапишет слот дня истинным значением (set, не max).
            for (int back = 1; back <= 8; back++) {
                Calendar dayCal = Calendar.getInstance();
                dayCal.set(Calendar.HOUR_OF_DAY, 0);
                dayCal.set(Calendar.MINUTE, 0);
                dayCal.set(Calendar.SECOND, 0);
                dayCal.set(Calendar.MILLISECOND, 0);
                dayCal.add(Calendar.DAY_OF_YEAR, -back);
                long dayStart = dayCal.getTimeInMillis();

                dayCal.add(Calendar.DAY_OF_YEAR, 1);
                long dayEnd = dayCal.getTimeInMillis();

                UsageMath.getFilteredExactTimes(context, dayStart, dayEnd);
            }
        } catch (Exception ignored) { }
        return Result.success();
    }
}
