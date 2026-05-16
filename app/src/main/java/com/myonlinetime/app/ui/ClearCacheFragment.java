package com.myonlinetime.app.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.Locale;

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

    // Палитра диаграммы
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
        
        // Инжектим кастомный график программно, чтобы не трогать твой XML
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

                // 4. Данные (весь DataDir минус кэш и фоны)
                long totalData = getDirSize(activity.getDataDir());
                sizeData = Math.max(0, totalData - sizeCache - sizeBg);

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
        textTotalSize.setText(String.format(Locale.getDefault(), "%.1f МБ", totalMb));

        float[] values = isGuest ? new float[]{sizeApp, sizeData, sizeCache} : new float[]{sizeApp, sizeData, sizeCache, sizeBg};
        int[] colors = isGuest ? new int[]{COLOR_APP, COLOR_DATA, COLOR_CACHE} : new int[]{COLOR_APP, COLOR_DATA, COLOR_CACHE, COLOR_BG};
        
        donutChart.setData(values, colors);

        buildListItem(getString(R.string.category_app), sizeApp, totalSize, COLOR_APP);
        buildListItem(getString(R.string.category_data), sizeData, totalSize, COLOR_DATA);
        buildListItem(getString(R.string.category_cache), sizeCache, totalSize, COLOR_CACHE);
        if (!isGuest) {
            buildListItem(getString(R.string.category_backgrounds), sizeBg, totalSize, COLOR_BG);
        }
    }

    private void buildListItem(String name, long size, long total, int color) {
        if (size <= 0) return;
        
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_storage_row, listContainer, false);
        View marker = row.findViewById(R.id.color_marker);
        TextView txtName = row.findViewById(R.id.text_category_name);
        TextView txtSize = row.findViewById(R.id.text_category_size);
        
        marker.setBackgroundColor(color);
        
        double sizeMb = size / (1024.0 * 1024.0);
        int percent = (int) Math.round((double) size / total * 100);
        
        txtName.setText(String.format(Locale.getDefault(), "%s %d%%", name, percent));
        txtSize.setText(String.format(Locale.getDefault(), "%.1f МБ", sizeMb));

        // Эффект облачка
        row.setOnClickListener(v -> {
            String msg = String.format(Locale.getDefault(), "%s: %.1f МБ (%d%% от общего)", name, sizeMb, percent);
            showCloudTooltip(v, msg);
        });

        listContainer.addView(row);
    }

    private void showCloudTooltip(View anchor, String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show(); 
        // Примечание: Для кастомного "облачка" поверх UI нужен PopupWindow, 
        // оставил Toast как безопасный fallback, если у тебя нет готового XML для облачка.
    }

    private void showClearModal(MainActivity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_clear_storage);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        CheckBox cbCache = dialog.findViewById(R.id.cb_clear_cache);
        CheckBox cbData = dialog.findViewById(R.id.cb_clear_data);
        CheckBox cbBg = dialog.findViewById(R.id.cb_clear_bg);
        Button btnConfirm = dialog.findViewById(R.id.btn_confirm_clear);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel_clear);

        if (isGuest) {
            cbBg.setVisibility(View.GONE);
            cbBg.setChecked(false);
        }

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
                    // Чистим кэш и файлы, но не удаляем SharedPreferences с токеном авторизации
                    File[] files = activity.getFilesDir().listFiles();
                    if (files != null) {
                        for (File f : files) if (!f.getName().startsWith("my_bg_")) deleteDir(f);
                    }
                }
                if (cbBg.isChecked() && !isGuest) {
                    activity.deleteMyBackgroundLocal();
                }

                new Handler(Looper.getMainLooper()).post(() -> startCalculatingSizes(activity));
            });
        });

        dialog.show();
    }

    // ==========================================
    // УТИЛИТЫ И КАСТОМНЫЙ ГРАФИК
    // ==========================================
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
            activity.headerTitle.setText(getString(R.string.header_settings_sub)); 
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

    // Внутренний класс для отрисовки кольцевой диаграммы с анимацией
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
            // Толщина кольца
            paint.setStrokeWidth(60f); 
            rectF = new RectF();
        }

        public void setData(float[] values, int[] colors) {
            this.values = values;
            this.colors = colors;
            total = 0;
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
                canvas.drawArc(rectF, startAngle, sweepAngle, false, paint);
                startAngle += sweepAngle;
            }
        }
    }
}
