package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;

import java.io.File;

public class ClearCacheFragment extends Fragment {

    private ProgressBar loadingSpinner;
    private View contentLayout;
    private LinearLayout listContainer;
    private DonutChartView donutChart;
    private TextView textTotalSize;
    private Button btnClear;

    private long sizeApp = 0;
    private long sizeData = 0;
    private long sizeCache = 0;
    private long sizeBg = 0;
    private boolean isGuest = true;

    // Палитра для диаграммы
    private final int COLOR_APP = Color.parseColor("#4A90E2");
    private final int COLOR_DATA = Color.parseColor("#50E3C2");
    private final int COLOR_CACHE = Color.parseColor("#F5A623");
    private final int COLOR_BG = Color.parseColor("#B8E986");

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

        isGuest = (activity == null || activity.vpsToken == null);

        btnClear.setOnClickListener(v -> showClearModal(activity));

        startCalculatingSizes(activity);

        return view;
    }

    private void startCalculatingSizes(MainActivity activity) {
        if (activity == null) return;
        
        loadingSpinner.setVisibility(View.VISIBLE);
        contentLayout.setVisibility(View.GONE);
        listContainer.removeAllViews();

        Utils.backgroundExecutor.execute(() -> {
            try {
                // 1. Приложение (размер APK)
                File apkFile = new File(activity.getApplicationInfo().publicSourceDir);
                sizeApp = apkFile.exists() ? apkFile.length() : 0;

                // 2. Кэш
                sizeCache = getDirSize(activity.getCacheDir());
                if (activity.getExternalCacheDir() != null) {
                    sizeCache += getDirSize(activity.getExternalCacheDir());
                }

                // 3. Фоны (только если авторизован)
                sizeBg = 0;
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

                // 4. Данные (Исключаем кэш и папку shared_prefs с токенами!)
                long totalData = getAppDataSizeSafe(activity);
                sizeData = Math.max(0, totalData - sizeBg);

            } catch (Exception e) { e.printStackTrace(); }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                loadingSpinner.setVisibility(View.GONE);
                contentLayout.setVisibility(View.VISIBLE);
                renderDashboard();
            });
        });
    }

    private void renderDashboard() {
        long totalSize = sizeApp + sizeData + sizeCache + (isGuest ? 0 : sizeBg);
        double totalMb = totalSize / (1024.0 * 1024.0);
        
        String unitMb = getString(R.string.unit_mb);
        textTotalSize.setText(String.format(getString(R.string.format_size_mb), totalMb, ""));

        float[] values = isGuest ? new float[]{sizeApp, sizeData, sizeCache} : new float[]{sizeApp, sizeData, sizeCache, sizeBg};
        int[] colors = isGuest ? new int[]{COLOR_APP, COLOR_DATA, COLOR_CACHE} : new int[]{COLOR_APP, COLOR_DATA, COLOR_CACHE, COLOR_BG};
        
        donutChart.setData(values, colors);

        buildListItem(getString(R.string.category_app), sizeApp, totalSize, COLOR_APP);
        buildListItem(getString(R.string.category_data), sizeData, totalSize, COLOR_DATA);
        buildListItem(getString(R.string.category_cache), sizeCache, totalSize, COLOR_CACHE);
        if (!isGuest) {
            buildListItem(getString(R.string.category_backgrounds), sizeBg, totalSize, COLOR_BG);
        }

        // Строка "Всего"
        buildTotalItem(getString(R.string.category_total), totalSize);
    }

    private void buildListItem(String name, long size, long total, int color) {
        if (size <= 0) return;
        
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(40, 30, 40, 30);
        
        // Красивая волна (ripple)
        row.setBackgroundResource(R.drawable.bg_app_card);
        row.setForeground(ContextCompat.getDrawable(requireContext(), androidx.appcompat.R.attr.selectableItemBackground));
        row.setClickable(true);
        row.setFocusable(true);

        View marker = new View(requireContext());
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(30, 30);
        markerParams.setMargins(0, 0, 30, 0);
        marker.setLayoutParams(markerParams);
        
        GradientDrawable markerDrawable = new GradientDrawable();
        markerDrawable.setShape(GradientDrawable.OVAL);
        markerDrawable.setColor(color);
        marker.setBackground(markerDrawable);

        TextView txtName = new TextView(requireContext());
        txtName.setTextColor(ContextCompat.getColor(requireContext(), R.color.textDynamic));
        txtName.setTextSize(16f);
        
        int percent = (int) Math.round((double) size / total * 100);
        txtName.setText(String.format("%s %s", name, getString(R.string.format_percent, percent)));
        
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        txtName.setLayoutParams(nameParams);

        TextView txtSize = new TextView(requireContext());
        txtSize.setTextColor(ContextCompat.getColor(requireContext(), R.color.textGrayDynamic));
        txtSize.setTextSize(14f);
        
        double sizeMb = size / (1024.0 * 1024.0);
        txtSize.setText(getString(R.string.format_size_mb, sizeMb, getString(R.string.unit_mb)));

        row.addView(marker);
        row.addView(txtName);
        row.addView(txtSize);

        // ИСТИННОЕ ОБЛАЧКО
        row.setOnClickListener(v -> {
            String msg = getString(R.string.format_tooltip, name, sizeMb, getString(R.string.unit_mb), percent);
            showCloudTooltip(v, msg);
        });

        listContainer.addView(row);
    }

    private void buildTotalItem(String name, long totalSize) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(40, 40, 40, 40);

        TextView txtName = new TextView(requireContext());
        txtName.setTextColor(ContextCompat.getColor(requireContext(), R.color.textDynamic));
        txtName.setTextSize(16f);
        txtName.setTypeface(null, android.graphics.Typeface.BOLD);
        txtName.setText(name);
        
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        txtName.setLayoutParams(nameParams);

        TextView txtSize = new TextView(requireContext());
        txtSize.setTextColor(ContextCompat.getColor(requireContext(), R.color.textDynamic));
        txtSize.setTextSize(16f);
        txtSize.setTypeface(null, android.graphics.Typeface.BOLD);
        
        double sizeMb = totalSize / (1024.0 * 1024.0);
        txtSize.setText(getString(R.string.format_size_mb, sizeMb, getString(R.string.unit_mb)));

        row.addView(txtName);
        row.addView(txtSize);
        listContainer.addView(row);
    }

    // === КАСТОМНОЕ ОБЛАЧКО (POPUP WINDOW) ===
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

    // === КАСТОМНОЕ МОДАЛЬНОЕ ОКНО ОЧИСТКИ ===
    private void showClearModal(MainActivity activity) {
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

        CheckBox cbCache = new CheckBox(activity);
        cbCache.setText(R.string.category_cache);
        cbCache.setTextColor(ContextCompat.getColor(activity, R.color.textDynamic));
        cbCache.setChecked(true);
        dialogLayout.addView(cbCache);

        CheckBox cbData = new CheckBox(activity);
        cbData.setText(R.string.category_data);
        cbData.setTextColor(ContextCompat.getColor(activity, R.color.textDynamic));
        dialogLayout.addView(cbData);

        CheckBox cbBg = new CheckBox(activity);
        cbBg.setText(R.string.category_backgrounds);
        cbBg.setTextColor(ContextCompat.getColor(activity, R.color.textDynamic));
        if (isGuest) {
            cbBg.setVisibility(View.GONE);
        } else {
            cbBg.setChecked(true);
            dialogLayout.addView(cbBg);
        }

        LinearLayout btnLayout = new LinearLayout(activity);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);
        btnLayout.setPadding(0, 40, 0, 0);

        Button btnCancel = new Button(activity);
        btnCancel.setText(R.string.btn_cancel);
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setTextColor(ContextCompat.getColor(activity, R.color.textGrayDynamic));
        
        Button btnConfirm = new Button(activity);
        btnConfirm.setText(R.string.btn_clear_data_confirm);
        btnConfirm.setBackgroundColor(Color.TRANSPARENT);
        btnConfirm.setTextColor(ContextCompat.getColor(activity, R.color.grapefruit));

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            btnConfirm.setEnabled(false);
            loadingSpinner.setVisibility(View.VISIBLE);
            contentLayout.setVisibility(View.GONE);
            dialog.dismiss();

            Utils.backgroundExecutor.execute(() -> {
                if (cbCache.isChecked()) {
                    deleteDir(activity.getCacheDir());
                    if (activity.getExternalCacheDir() != null) deleteDir(activity.getExternalCacheDir());
                }
                if (cbData.isChecked()) {
                    clearAppDataSafe(activity);
                }
                if (cbBg.isChecked() && !isGuest) {
                    activity.deleteMyBackgroundLocal();
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
        }
        dialog.show();
    }

    // ==========================================
    // БЕЗОПАСНАЯ ОЧИСТКА ДАННЫХ (БЕЗ РАЗЛОГИНА)
    // ==========================================
    private long getAppDataSizeSafe(Context context) {
        long size = 0;
        File dataDir = new File(context.getApplicationInfo().dataDir);
        if (dataDir.exists() && dataDir.isDirectory()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    // Пропускаем кэш и папку shared_prefs (там токены авторизации)
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
                    // Ни в коем случае не удаляем shared_prefs, чтобы юзер остался в аккаунте
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
    // ВЬЮШКА: АНИМИРОВАННАЯ ДИАГРАММА
    // ==========================================
    private static class DonutChartView extends View {
        private Paint paint;
        private RectF rectF;
        private float[] values;
        private int[] colors;
        private float total = 0;
        private float animationProgress = 0f;

        public DonutChartView(Context context) {
            super(context);
            init();
        }

        private void init() {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(65f); // Идеальная толщина кольца
            rectF = new RectF();
        }

        public void setData(float[] values, int[] colors) {
            this.values = values;
            this.colors = colors;
            total = 0;
            for (float v : values) total += v;

            // Плавная анимация закручивания
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
                
                // Чтобы округлые края разных дуг не наслаивались криво друг на друга
                if (sweepAngle > 0) {
                    canvas.drawArc(rectF, startAngle, sweepAngle - 2f, false, paint);
                }
                startAngle += sweepAngle;
            }
        }
    }
}
