package com.myonlinetime.app.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;

public class DailySnapshotWorker extends Worker {

    public DailySnapshotWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context context = getApplicationContext();

            // Проходим последние 8 ЗАВЕРШЁННЫХ дней (back=1 — вчера, ... back=8).
            // Для каждого вызываем getFilteredExactTimes по СУТОЧНОМУ окну: пока
            // события за день ещё живы в системе (~7–14 дней), метод сам запишет
            // точное значение дня в UsageSafeCache (set, не max), т.е. "заморозит"
            // истёкший день на правде. Когда система вытеснит события, этот снапшот
            // станет источником для графика и истории — недосчёт на старых днях
            // больше не появится.
            for (int back = 1; back <= 8; back++) {
                Calendar startCal = Calendar.getInstance();
                startCal.add(Calendar.DAY_OF_YEAR, -back);
                startCal.set(Calendar.HOUR_OF_DAY, 0);
                startCal.set(Calendar.MINUTE, 0);
                startCal.set(Calendar.SECOND, 0);
                startCal.set(Calendar.MILLISECOND, 0);
                long dayStart = startCal.getTimeInMillis();

                // Конец дня = начало следующего дня. Считаем через Calendar (а не
                // dayStart + 86400000), чтобы корректно пережить переход на летнее/
                // зимнее время — сутки могут быть 23 или 25 часов.
                Calendar endCal = (Calendar) startCal.clone();
                endCal.add(Calendar.DAY_OF_YEAR, 1);
                long dayEnd = endCal.getTimeInMillis();

                // Никаких пушей/сети — только чтение системной статистики и запись
                // в локальный защитный кэш внутри getFilteredExactTimes.
                UsageMath.getFilteredExactTimes(context, dayStart, dayEnd);
            }

            return Result.success();
        } catch (Throwable t) {
            // Снимок — не критичная операция; при сбое просто повторим в следующий раз.
            return Result.success();
        }
    }
}
