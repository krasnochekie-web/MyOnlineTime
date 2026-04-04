package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.core.content.ContextCompat;
import com.myonlinetime.app.R;

public class WeeklyBarChartView extends View {

    private Paint barPaint, barSelectedPaint, textPaint, linePaint;
    private long[] dataMillis = new long[7];
    private String[] labels = new String[]{"", "", "", "", "", "", ""};
    private int selectedIndex = 6; 
    private float animationProgress = 0f;
    private long maxMillis = 1; 

    private OnBarSelectedListener listener;
    private RectF[] barRects = new RectF[7];

    public interface OnBarSelectedListener {
        void onBarSelected(int index);
    }

    public WeeklyBarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setColor(ContextCompat.getColor(context, R.color.chartBarNormal));
        barPaint.setStyle(Paint.Style.FILL);

        barSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barSelectedPaint.setColor(ContextCompat.getColor(context, R.color.chartBarSelected));
        barSelectedPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(ContextCompat.getColor(context, R.color.textGrayDynamic));
        textPaint.setTextSize(36f); 
        textPaint.setTextAlign(Paint.Align.CENTER);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(ContextCompat.getColor(context, R.color.chartLineDynamic));
        linePaint.setStrokeWidth(2f);

        for (int i = 0; i < 7; i++) barRects[i] = new RectF();
    }

    public void setListener(OnBarSelectedListener listener) {
        this.listener = listener;
    }

    public void setData(long[] millis, String[] dayLabels) {
        this.dataMillis = millis;
        this.labels = dayLabels;
        this.maxMillis = 1;
        for (long m : millis) if (m > maxMillis) maxMillis = m;

        // Используем суффикс L для предотвращения переполнения int
        long hours = (maxMillis / (1000L * 60 * 60)) + 1;
        
        // Округляем до четного числа, чтобы центральная линия (50%) всегда была точной
        if (hours % 2 != 0) {
            hours++;
        }
        // Устанавливаем минимальную шкалу в 4 часа (получим линии: 0, 2, 4)
        if (hours < 4) hours = 4; 

        maxMillis = hours * 1000L * 60 * 60;

        startAnimation();
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < 7) {
            this.selectedIndex = index;
            invalidate();
        }
    }

    private void startAnimation() {
        // 1. Прячем графики до начала анимации
        animationProgress = 0f;
        invalidate();

        // 2. Ждем 200 мс, пока Android отрисует список, и стартуем плавно
        postDelayed(() -> {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(700);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                animationProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }, 200);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        
        float rightPadding = 100f; 
        float bottomPadding = 60f; 
        float topPadding = 50f; 
        
        float chartWidth = width - rightPadding;
        float chartHeight = height - bottomPadding - topPadding; 

        // Теперь maxHours гарантированно четное число
        long maxHours = maxMillis / (1000L * 60 * 60);
        
        // 1. Рисуем линии разметки
        for (int i = 0; i <= 2; i++) {
            float y = topPadding + chartHeight - (chartHeight / 2f * i); 
            canvas.drawLine(0, y, chartWidth, y, linePaint);
            
            // Вычисляем точное значение для лейбла
            long labelValue = (maxHours / 2) * i;
            String hourText = getContext().getString(R.string.format_chart_hour, labelValue);
            
            textPaint.setTextAlign(Paint.Align.LEFT);
            // Выравниваем текст чуть аккуратнее относительно линии
            canvas.drawText(hourText, chartWidth + 15, y + (textPaint.getTextSize() / 3f), textPaint);
        }

        float barWidth = chartWidth / 7f * 0.6f; 
        float spacing = chartWidth / 7f;

        // 2. Рисуем столбцы
        for (int i = 0; i < 7; i++) {
            float xCenter = (spacing * i) + (spacing / 2f);
            float barHeight = ((float) dataMillis[i] / maxMillis) * chartHeight * animationProgress;
            
            float left = xCenter - (barWidth / 2f);
            float right = xCenter + (barWidth / 2f);
            float top = topPadding + chartHeight - barHeight; 
            
            // Поднимаем низ столбца на половину толщины линии, 
            // чтобы он стоял ровно на ней, а не перекрывал её центр
            float bottom = topPadding + chartHeight - (linePaint.getStrokeWidth() / 2f); 

            barRects[i].set(left, top, right, bottom);

            Paint currentPaint = (i == selectedIndex) ? barSelectedPaint : barPaint;
            canvas.drawRect(barRects[i], currentPaint);
            
            // Текст дней недели
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(labels[i], xCenter, height - 10, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float spacing = (getWidth() - 100f) / 7f;
            int clickedIndex = (int) (x / spacing);
            if (clickedIndex >= 0 && clickedIndex < 7) {
                setSelectedIndex(clickedIndex);
                if (listener != null) listener.onBarSelected(clickedIndex);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
