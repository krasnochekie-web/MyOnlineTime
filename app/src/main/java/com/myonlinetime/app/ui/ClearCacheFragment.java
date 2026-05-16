package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.CompoundButtonCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ClearCacheFragment extends Fragment {

    private ProgressBar loadingSpinner;
    private View contentLayout;
    private LinearLayout listContainer;
    private DonutChartView donutChart;
    private TextView textTotalSize;
    private Button btnClear;

    private boolean isGuest = true;
    private List<StorageCategory> categories = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();

    // Палитра для диаграммы
    private final int COLOR_APP = Color.parseColor("#4A90E2");
    private final int COLOR_DATA = Color.parseColor("#50E3C2");
    private final int COLOR_CACHE = Color.parseColor("#F5A623");
    private final int COLOR_BG = Color.parseColor("#B8E986");

    private static class StorageCategory {
        String name;
        long size;
        int color;
        int type; // 0=APP, 1=DATA, 2=CACHE, 3=BG
        boolean isChecked = true;

        StorageCategory(String name, long size, int color, int type) {
            this.name = name;
            this.size = size;
            this.color = color;
            this.type = type;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_clear_cache, container, false);
        MainActivity activity = (MainActivity) getActivity();

        setupHeader(activity);

        loadingSpinner = view.findViewById(R.id.loading_spinner);
        contentLayout = view.findViewById(R.id.content_layout);
        listContainer = view.findViewById(R.id.list_container);
        textTotalSize = view.findViewById(R.id.text_total_size);
        btnClear = view.findViewById(R.id.btn_open_clear_modal);
        
        ViewGroup chartContainer = view.findViewById(R.id.chart_container);
        donutChart = new DonutChartView(requireContext());
        chartContainer.addView(donutChart, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // СЛУШАТЕЛЬ ДИАГРАММЫ: Делаем волну на нужном пункте списка
        donutChart.setOnSegmentTouchListener((index, isDown) -> {
            if (index >= 0 && index < rowViews.size()) {
                View row = rowViews.get(index);
                row.setPressed(isDown); // Запускает Ripple-эффект
            }
        });

        isGuest = (activity == null || activity.vpsToken == null);

        btnClear.setOnClickListener(v -> showClearConfirmModal(activity));

        startCalculatingSizes(activity);

        return view;
    }

    private void startCalculatingSizes(MainActivity activity) {
        if (activity == null) return;
        
        loadingSpinner.setVisibility(View.VISIBLE);
        contentLayout.setVisibility(View.GONE);
        listContainer.removeAllViews();
        categories.clear();
        rowViews.clear();

        Utils.backgroundExecutor.execute(() -> {
            long sizeApp = 0, sizeData = 0, sizeCache = 0, sizeBg = 0;

            try {
                File apkFile = new File(activity.getApplicationInfo().publicSourceDir);
                sizeApp = apkFile.exists() ? apkFile.length() : 0;

                sizeCache = getDirSize(activity.getCacheDir());
                if (activity.getExternalCacheDir() != null) {
                    sizeCache += getDirSize(activity.getExternalCacheDir());
                }

                if (!isGuest) {
                    String uid = GoogleSignIn.getLastSignedInAccount(activity) != null ? GoogleSignIn.getLastSignedInAccount(activity).getId() : "";
                    File dir = activity.getFilesDir();
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().startsWith("my_bg_" + uid)) sizeBg += f.length();
                        }
                    }
                }

                long totalData = getAppDataSizeSafe(activity);
                sizeData = Math.max(0, totalData - sizeBg);

            } catch (Exception e) { e.printStackTrace(); }

            categories.add(new StorageCategory(getString(R.string.category_app), sizeApp, COLOR_APP, 0));
            categories.add(new StorageCategory(getString(R.string.category_data), sizeData, COLOR_DATA, 1));
            categories.add(new StorageCategory(getString(R.string.category_cache), sizeCache, COLOR_CACHE, 2));
            if (!isGuest) {
                categories.add(new StorageCategory(getString(R.string.category_backgrounds), sizeBg, COLOR_BG, 3));
            }

            Collections.sort(categories, (a, b) -> Long.compare(b.size, a.size));

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                loadingSpinner.setVisibility(View.GONE);
                contentLayout.setVisibility(View.VISIBLE);
                renderDashboard();
            });
        });
    }

    private void renderDashboard() {
        long totalSize = 0;
        for (StorageCategory cat : categories) totalSize += cat.size;
        
        double totalMb = totalSize / (1024.0 * 1024.0);
        textTotalSize.setText(String.format(getString(R.string.format_size_mb), totalMb, ""));

        float[] values = new float[categories.size()];
        int[] colors = new int[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            values[i] = categories.get(i).size;
            colors[i] = categories.get(i).color;
            buildListItem(categories.get(i), totalSize);
        }
        donutChart.setData(values, colors);

        buildTotalItem(getString(R.string.category_total), totalSize);
        
        updateClearButtonText(); // Первичный расчет кнопки
    }

    private void updateClearButtonText() {
        long totalSelected = 0;
        for (StorageCategory cat : categories) {
            if (cat.type > 0 && cat.isChecked) totalSelected += cat.size;
        }
        
        if (totalSelected > 0) {
            double sizeMb = totalSelected / (1024.0 * 1024.0);
            btnClear.setText(String.format(Locale.getDefault(), "%s %.1f %s", getString(R.string.btn_clear_cache), sizeMb, getString(R.string.unit_mb)));
        } else {
            btnClear.setText(R.string.btn_clear_cache);
        }
    }

    private void buildListItem(StorageCategory category, long totalSize) {
        if (category.size <= 0) return;
        
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_storage_row, listContainer, false);
        rowViews.add(row);
        
        View marker = row.findViewById(R.id.color_marker);
        CheckBox cb = row.findViewById(R.id.checkbox_marker);
        TextView txtName = row.findViewById(R.id.text_category_name);
        TextView txtSize = row.findViewById(R.id.text_category_size);

        int percent = (int) Math.round((double) category.size / totalSize * 100);
        txtName.setText(String.format("%s %s", category.name, getString(R.string.format_percent, percent)));
        
        double sizeMb = category.size / (1024.0 * 1024.0);
        txtSize.setText(getString(R.string.format_size_mb, sizeMb, getString(R.string.unit_mb)));

        if (category.type == 0) { // Приложение
            cb.setVisibility(View.GONE);
            marker.setVisibility(View.VISIBLE);
            
            GradientDrawable markerDrawable = new GradientDrawable();
            markerDrawable.setShape(GradientDrawable.RECTANGLE); 
            markerDrawable.setColor(category.color);
            marker.setBackground(markerDrawable);
            
            row.setOnClickListener(v -> {
                String msg = getString(R.string.format_tooltip, category.name, sizeMb, getString(R.string.unit_mb), percent);
                showCloudTooltip(v, msg);
            });
        } else {
            marker.setVisibility(View.GONE);
            cb.setVisibility(View.VISIBLE);
            CompoundButtonCompat.setButtonTintList(cb, ColorStateList.valueOf(category.color));
            cb.setChecked(category.isChecked);
            
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                category.isChecked = isChecked;
                updateClearButtonText(); // Динамический текст на кнопке
            });
            
            row.setOnClickListener(v -> {
                cb.setChecked(!cb.isChecked());
                String msg = getString(R.string.format_tooltip, category.name, sizeMb, getString(R.string.unit_mb), percent);
                showCloudTooltip(v, msg);
            });
            
            cb.setOnClickListener(v -> {
                String msg = getString(R.string.format_tooltip, category.name, sizeMb, getString(R.string.unit_mb), percent);
                showCloudTooltip(row, msg);
            });
        }

        listContainer.addView(row);
    }

    private void buildTotalItem(String name, long totalSize) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_storage_row, listContainer, false);
        
        row.findViewById(R.id.color_marker).setVisibility(View.GONE);
        row.findViewById(R.id.checkbox_marker).setVisibility(View.GONE);
        
        row.setClickable(false);
        row.setBackgroundColor(Color.TRANSPARENT);

        TextView txtName = row.findViewById(R.id.text_category_name);
        TextView txtSize = row.findViewById(R.id.text_category_size);

        txtName.setText(name);
        txtName.setTypeface(null, android.graphics.Typeface.BOLD);
        
        txtSize.setTextColor(ContextCompat.getColor(requireContext(), R.color.textDynamic));
        txtSize.setTypeface(null, android.graphics.Typeface.BOLD);
        
        double sizeMb = totalSize / (1024.0 * 1024.0);
        txtSize.setText(getString(R.string.format_size_mb, sizeMb, getString(R.string.unit_mb)));

        listContainer.addView(row);
    }

    private void showCloudTooltip(View anchor, String message) {
        TextView tv = new TextView(requireContext());
        tv.setText(message);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14f);
        tv.setPadding(40, 20, 40, 20);
        tv.setGravity(Gravity.CENTER);
        
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#E6202020")); 
        gd.setCornerRadius(30f);
        tv.setBackground(gd);

        PopupWindow popupWindow = new PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setElevation(20f);
        popupWindow.setAnimationStyle(android.R.style.Animation_Toast);
        
        anchor.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        
        int xOffset = (anchor.getWidth() - tv.getMeasuredWidth()) / 2;
        int yOffset = -anchor.getHeight() - tv.getMeasuredHeight() - 20;
        
        popupWindow.showAsDropDown(anchor, xOffset, yOffset);
    }

    private void showClearConfirmModal(MainActivity activity) {
        long totalSelected = 0;
        for (StorageCategory cat : categories) {
            if (cat.type > 0 && cat.isChecked) totalSelected += cat.size;
        }

        if (totalSelected == 0) {
            Toast.makeText(activity, "Нет выбранных категорий", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        LinearLayout dialogLayout = new LinearLayout(activity);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(60, 60, 60, 60);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(activity, R.color.bgDynamic));
        bg.setCornerRadius(40f);
        dialogLayout.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(R.string.title_clear_storage);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(activity, R.color.textDynamic));
        title.setPadding(0, 0, 0, 40);
        dialogLayout.addView(title);

        TextView message = new TextView(activity);
        double sizeMb = totalSelected / (1024.0 * 1024.0);
        message.setText(String.format(Locale.getDefault(), "Очистить выбранные данные (%.1f МБ)?", sizeMb));
        message.setTextColor(ContextCompat.getColor(activity, R.color.textDynamic));
        message.setTextSize(16f);
        dialogLayout.addView(message);

        LinearLayout btnLayout = new LinearLayout(activity);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);
        btnLayout.setPadding(0, 40, 0, 0);

        // Получаем системный эффект волны (ripple)
        TypedValue rippleValue = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true);

        Button btnCancel = new Button(activity);
        btnCancel.setText(R.string.btn_cancel);
        btnCancel.setBackgroundResource(rippleValue.resourceId); // Эффект волны
        btnCancel.setTextColor(ContextCompat.getColor(activity, R.color.textGrayDynamic));
        
        Button btnConfirm = new Button(activity);
        btnConfirm.setText(R.string.btn_clear_data_confirm);
        btnConfirm.setBackgroundResource(rippleValue.resourceId); // Эффект волны
        btnConfirm.setTextColor(ContextCompat.getColor(activity, R.color.grapefruit));

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            btnConfirm.setEnabled(false);
            loadingSpinner.setVisibility(View.VISIBLE);
            contentLayout.setVisibility(View.GONE);
            dialog.dismiss();

            Utils.backgroundExecutor.execute(() -> {
                for (StorageCategory cat : categories) {
                    if (cat.isChecked) {
                        if (cat.type == 2) { 
                            deleteDir(activity.getCacheDir());
                            if (activity.getExternalCacheDir() != null) deleteDir(activity.getExternalCacheDir());
                        }
                        if (cat.type == 1) clearAppDataSafe(activity); 
                        if (cat.type == 3 && !isGuest) activity.deleteMyBackgroundLocal(); 
                    }
                }
                new Handler(Looper.getMainLooper()).post(() -> startCalculatingSizes(activity));
            });
        });

        btnLayout.addView(btnCancel);
        btnLayout.addView(btnConfirm);
        dialogLayout.addView(btnLayout);

        dialog.setContentView(dialogLayout);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.85), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog; // Анимация появления
        }
        dialog.show();
    }

    private long getAppDataSizeSafe(Context context) {
        long size = 0;
        File dataDir = new File(context.getApplicationInfo().dataDir);
        if (dataDir.exists() && dataDir.isDirectory()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().equals("cache") || f.getName().equals("code_cache") || f.getName().equals("shared_prefs")) continue;
                    size += getDirSize(f);
                }
            }
        }
        return size;
    }

    private void clearAppDataSafe(Context context) {
        File dataDir = new File(context.getApplicationInfo().dataDir);
        if (dataDir.exists() && dataDir.isDirectory()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().equals("cache") || f.getName().equals("code_cache") || f.getName().equals("shared_prefs")) continue;
                    deleteDir(f);
                }
            }
        }
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) size += file.length();
                    else if (file.isDirectory()) size += getDirSize(file);
                }
            }
        } else if (dir != null && dir.isFile()) {
            size = dir.length();
        }
        return size;
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) return false;
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }

    private void setupHeader(MainActivity activity) {
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.title_clear_storage)); 
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.headerManager.resetHeader();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(false); 
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            setupHeader(activity);
            activity.updateGlobalBackground(false);
        }
    }

    // ==========================================
    // ВЬЮШКА: АНИМИРОВАННАЯ ИНТЕРАКТИВНАЯ ДИАГРАММА
    // ==========================================
    private static class DonutChartView extends View {
        private Paint paint;
        private Paint textPaint;
        private RectF rectF;
        private float[] values;
        private int[] colors;
        private float total = 0;
        private float animationProgress = 0f;
        private int activeIndex = -1;

        public interface OnSegmentTouchListener {
            void onSegmentTouched(int index, boolean isDown);
        }
        private OnSegmentTouchListener touchListener;

        public DonutChartView(Context context) {
            super(context);
            init();
        }

        public void setOnSegmentTouchListener(OnSegmentTouchListener listener) {
            this.touchListener = listener;
        }

        private void init() {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT); 
            paint.setStrokeWidth(65f);
            
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(36f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            
            rectF = new RectF();
        }

        public void setData(float[] values, int[] colors) {
            this.values = values;
            this.colors = colors;
            total = 0;
            activeIndex = -1;
            for (float v : values) total += v;

            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                animationProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (values == null || total == 0 || animationProgress < 1f) return false;

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float dx = event.getX() - cx;
            float dy = event.getY() - cy;
            
            float radius = Math.min(getWidth(), getHeight()) / 2f - paint.getStrokeWidth();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float halfStroke = paint.getStrokeWidth() / 2f;

            if (distance >= radius - halfStroke && distance <= radius + halfStroke) {
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (angle < 0) angle += 360f;
                
                float touchAngle = angle + 90f; 
                if (touchAngle >= 360f) touchAngle -= 360f;

                if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                    float currentAngle = 0f;
                    int touchedIndex = -1;
                    for (int i = 0; i < values.length; i++) {
                        float sweep = (values[i] / total) * 360f;
                        if (touchAngle >= currentAngle && touchAngle < currentAngle + sweep) {
                            touchedIndex = i;
                            break;
                        }
                        currentAngle += sweep;
                    }
                    
                    if (touchedIndex != activeIndex) {
                        activeIndex = touchedIndex;
                        invalidate();
                        if (touchListener != null) touchListener.onSegmentTouched(activeIndex, true);
                    }
                    return true;
                }
            }
            
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (activeIndex != -1) {
                    if (touchListener != null) touchListener.onSegmentTouched(activeIndex, false);
                    activeIndex = -1;
                    invalidate();
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (values == null || total == 0) return;

            float width = getWidth();
            float height = getHeight();
            float radius = Math.min(width, height) / 2f - paint.getStrokeWidth();
            
            rectF.set(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius);

            float startAngle = -90f;
            for (int i = 0; i < values.length; i++) {
                paint.setColor(colors[i]);
                float sweepAngle = (values[i] / total) * 360f * animationProgress;
                
                if (sweepAngle > 0) {
                    canvas.save();
                    
                    if (i == activeIndex) {
                        double midRad = Math.toRadians(startAngle + sweepAngle / 2f);
                        canvas.translate((float) Math.cos(midRad) * 15f, (float) Math.sin(midRad) * 15f);
                    }
                    
                    canvas.drawArc(rectF, startAngle, sweepAngle, false, paint);
                    
                    if (sweepAngle >= 15f && animationProgress > 0.8f) {
                        double midRad = Math.toRadians(startAngle + sweepAngle / 2f);
                        float x = width / 2f + (float) Math.cos(midRad) * radius;
                        float y = height / 2f + (float) Math.sin(midRad) * radius - ((textPaint.descent() + textPaint.ascent()) / 2f);
                        
                        int pct = (int) Math.round((values[i] / total) * 100);
                        if (pct > 0) {
                            canvas.drawText(pct + "%", x, y, textPaint);
                        }
                    }
                    
                    canvas.restore();
                }
                startAngle += sweepAngle;
            }
        }
    }
}
