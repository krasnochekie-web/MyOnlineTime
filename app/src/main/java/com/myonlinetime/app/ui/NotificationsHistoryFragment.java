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

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
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

    private Runnable hideBgRunnable;
    private RecyclerView recycler;
    private TextView emptyText;
    private ProgressBar loadingSpinner;
    private NotificationsAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // === ИСПРАВЛЕНИЕ: ЖЕЛЕЗОБЕТОННОЕ ОБНОВЛЕНИЕ РЕАЛ-ТАЙМ ===
    private final android.content.BroadcastReceiver pushReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            if ("UPDATE_BADGE_BROADCAST".equals(intent.getAction())) {
                // Даем 150мс сервису, чтобы он 100% успел сделать commit() на диск
                uiHandler.postDelayed(() -> {
                    loadFromCacheOnly(); 
                    
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) activity.updateNotificationBadge();
                }, 150);
            }
        }
    };

    private String getCacheKey() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
        String uid = account != null ? account.getId() : "guest";
        return "notif_history_array_" + uid;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        recycler = view.findViewById(R.id.recycler_notifications);
        emptyText = view.findViewById(R.id.empty_notif_text);
        loadingSpinner = view.findViewById(R.id.loading_spinner);
        
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(20);
        recycler.getRecycledViewPool().setMaxRecycledViews(NotificationModels.NotificationItem.TYPE_TIME, 20);
        recycler.getRecycledViewPool().setMaxRecycledViews(NotificationModels.NotificationItem.TYPE_FOLLOWER, 20);

        loadHistory();

        return view;
    }

    // НОВЫЙ МЕТОД: Читает только из кэша и принудительно перерисовывает адаптер
    private void loadFromCacheOnly() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !isAdded()) return;
        
        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");
        
        if (!cachedJson.equals("[]") && cachedJson.length() > 5) {
            parseAndDisplay(cachedJson, activity, true); // true = принудительно обновить UI
        }
    }

    private void loadHistory() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");
        
        boolean hasCache = !cachedJson.equals("[]") && cachedJson.length() > 5;
        
        if (hasCache) {
            loadingSpinner.setVisibility(View.GONE);
            parseAndDisplay(cachedJson, activity, false);
        } else {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            loadingSpinner.setVisibility(View.VISIBLE);
        }

        if (activity.vpsToken == null) {
            if (!hasCache) showEmptyState();
            return;
        }

        VpsApi.getNotificationsHistory(activity.vpsToken, new VpsApi.Callback() {
            @Override
            public void onSuccess(String result) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    loadingSpinner.setVisibility(View.GONE);
                    prefs.edit().putString(cacheKey, result).apply();
                    parseAndDisplay(result, activity, false);
                });
            }

            @Override
            public void onError(String error) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    loadingSpinner.setVisibility(View.GONE);
                    if (!hasCache) {
                        showEmptyState();
                        Toast.makeText(getContext(), getString(R.string.err_server) + " " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // ИЗМЕНЕННЫЙ МЕТОД: Добавлен параметр forceRefresh
    private void parseAndDisplay(String jsonResult, MainActivity activity, boolean forceRefresh) {
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
                
                // Если forceRefresh == true, мы жестко пересоздаем адаптер, чтобы новые данные точно появились
                if (adapter == null || forceRefresh) {
                    adapter = new NotificationsAdapter(items, activity);
                    recycler.setAdapter(adapter);
                } else {
                    adapter.updateItems(items);
                }
                
                if (hasUnread && activity.vpsToken != null) {
                    markAllAsRead(activity, array, getCacheKey());
                }
            }
        } catch (Exception e) {
            showEmptyState(); 
        }
    }

    private void showEmptyState() {
        loadingSpinner.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void markAllAsRead(MainActivity activity, JSONArray array, String cacheKey) {
        try {
            for (int i = 0; i < array.length(); i++) {
                array.getJSONObject(i).put("isRead", true);
            }
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(cacheKey, array.toString()).apply();
        } catch (Exception ignored) {}

        VpsApi.markNotificationsRead(activity.vpsToken, new VpsApi.Callback() {
            @Override public void onSuccess(String result) {}
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
            
            activity.updateNotificationBadge();
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
        
        // Регистрируем локальный приемник для мгновенных обновлений
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(pushReceiver, new android.content.IntentFilter("UPDATE_BADGE_BROADCAST"));

        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
                };
            }
            if (getView() != null) getView().postDelayed(hideBgRunnable, 300);
            else activity.updateGlobalBackground(false);
            
            // Если мы вернулись в приложение и история открыта - гасим бейдж
            activity.updateNotificationBadge();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(pushReceiver);
        } catch (Exception ignored) {}
    }
    }
            
