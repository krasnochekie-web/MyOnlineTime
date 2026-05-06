package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.NotificationModels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class NotificationsHistoryFragment extends Fragment {

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_HISTORY = "notif_history_array";

    private Runnable hideBgRunnable;
    private RecyclerView recycler;
    private TextView emptyText;
    private ProgressBar loadingSpinner;
    private NotificationsAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        recycler = view.findViewById(R.id.recycler_notifications);
        emptyText = view.findViewById(R.id.empty_notif_text);
        loadingSpinner = view.findViewById(R.id.loading_spinner);
        
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        loadHistory();

        return view;
    }

    private void loadHistory() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || activity.vpsToken == null) {
            showEmptyState();
            return;
        }

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // === 1. МГНОВЕННО ГРУЗИМ КЭШ (Если интернета не будет, останется это) ===
        String cachedJson = prefs.getString(KEY_HISTORY, "[]");
        boolean hasCache = !cachedJson.equals("[]");
        
        if (hasCache) {
            parseAndDisplay(cachedJson, false, activity);
        } else {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
        }

        // Показываем крутилочку (даже если кэш есть, показываем, что ищем свежие данные)
        loadingSpinner.setVisibility(View.VISIBLE);

        // === 2. ИДЕМ НА СЕРВЕР ЗА СВЕЖИМИ ДАННЫМИ ===
        VpsApi.getNotificationsHistory(activity.vpsToken, new VpsApi.Callback() {
            @Override
            public void onSuccess(String result) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    loadingSpinner.setVisibility(View.GONE);
                    
                    // Сохраняем свежие данные в кэш для будущих запусков без интернета
                    prefs.edit().putString(KEY_HISTORY, result).apply();
                    
                    parseAndDisplay(result, true, activity);
                });
            }

            @Override
            public void onError(String error) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    loadingSpinner.setVisibility(View.GONE);
                    
                    if (hasCache) {
                        // Если кэш есть, просто тихо говорим, что мы в офлайне без хардкора!
                        Toast.makeText(getContext(), getString(R.string.err_server) + " " + getString(R.string.notif_offline_mode), Toast.LENGTH_SHORT).show();
                    } else {
                        // Если и кэша нет, и сервера нет — показываем пустой экран
                        showEmptyState();
                        Toast.makeText(getContext(), getString(R.string.err_server) + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void parseAndDisplay(String jsonResult, boolean isFromServer, MainActivity activity) {
        try {
            JSONArray array = new JSONArray(jsonResult);
            List<NotificationModels.NotificationItem> items = new ArrayList<>();
            boolean hasUnread = false;

            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String type = obj.optString("type", "time");
                
                if (!obj.optBoolean("isRead", false)) hasUnread = true;

                if ("time".equals(type)) {
                    items.add(new NotificationModels.TimeNotification(
                            obj.optString("mainText"),
                            obj.optString("actionText"),
                            obj.optLong("timestamp")
                    ));
                } else if ("follower".equals(type)) {
                    items.add(new NotificationModels.FollowerNotification(
                            obj.optLong("timestamp"),
                            obj.optString("uid"),
                            obj.optString("nickname"),
                            obj.optString("photo"),
                            obj.optBoolean("isFollowing", false)
                    ));
                }
            }

            if (items.isEmpty()) {
                showEmptyState();
            } else {
                recycler.setVisibility(View.VISIBLE);
                emptyText.setVisibility(View.GONE);
                
                if (adapter == null) {
                    adapter = new NotificationsAdapter(items, activity);
                    recycler.setAdapter(adapter);
                } else {
                    adapter.updateItems(items);
                }
                
                // Если данные пришли с сервера и есть непрочитанные - помечаем прочитанными
                if (isFromServer && hasUnread) {
                    markAllAsRead(activity, array);
                }
            }
        } catch (Exception e) {
            if (!isFromServer) showEmptyState(); 
        }
    }

    private void showEmptyState() {
        loadingSpinner.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void markAllAsRead(MainActivity activity, JSONArray array) {
        VpsApi.markNotificationsRead(activity.vpsToken, new VpsApi.Callback() {
            @Override public void onSuccess(String result) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    // Обновляем локальный кэш, чтобы красные точки исчезли и в офлайне
                    try {
                        for (int i = 0; i < array.length(); i++) {
                            array.getJSONObject(i).put("isRead", true);
                        }
                        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
                    } catch (Exception ignored) {}

                    activity.updateNotificationBadge();
                });
            }
            @Override public void onError(String error) {}
        });
    }

    private void setupHeader() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.title_notifications));
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 

            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) bellBtn.setVisibility(View.GONE);

            View bellContainer = activity.findViewById(R.id.header_bell_container);
            if (bellContainer != null) bellContainer.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.headerManager.resetHeader(); 
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        if (!hidden) {
            setupHeader(); 
            loadHistory(); 
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
                };
            }
            if (getView() != null) getView().postDelayed(hideBgRunnable, 300);
            else activity.updateGlobalBackground(false);
        } else {
            if (hideBgRunnable != null && getView() != null) getView().removeCallbacks(hideBgRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
                };
            }
            if (getView() != null) getView().postDelayed(hideBgRunnable, 300);
            else activity.updateGlobalBackground(false);
        }
    }
}
