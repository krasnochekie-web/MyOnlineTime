package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.NotificationModels;
import com.myonlinetime.app.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NotificationsHistoryFragment extends Fragment {

    private static final String PREFS_NAME = "AppPrefs";

    private Runnable hideBgRunnable;
    private RecyclerView recycler;
    private SwipeRefreshLayout swipeRefresh; // Заветная "тянучка"
    private TextView emptyText;
    private ProgressBar loadingSpinner;
    private NotificationsAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sharedPrefs, key) -> {
        if (key != null && key.equals(getCacheKey())) {
            uiHandler.post(() -> {
                if (isAdded()) {
                    loadFromCacheOnly();
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) activity.updateNotificationBadge();
                }
            });
        }
    };

    private final android.content.BroadcastReceiver pushReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            if ("UPDATE_BADGE_BROADCAST".equals(intent.getAction())) {
                uiHandler.post(() -> {
                    if (isAdded()) {
                        loadFromCacheOnly(); 
                        MainActivity activity = (MainActivity) getActivity();
                        if (activity != null) activity.updateNotificationBadge();
                    }
                });
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

        // === ИНТЕГРАЦИЯ SWIPE TO REFRESH ===
        swipeRefresh = new SwipeRefreshLayout(requireContext());
        swipeRefresh.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.grapefruit)); // Грейпфрутовый спиннер
        
        ViewGroup parent = (ViewGroup) recycler.getParent();
        int index = parent.indexOfChild(recycler);
        parent.removeView(recycler);
        swipeRefresh.addView(recycler);
        parent.addView(swipeRefresh, index);

        swipeRefresh.setOnRefreshListener(() -> {
            loadHistory();
        });
        // ===================================

        loadHistory();

        return view;
    }

    // === ИСПРАВЛЕНИЕ: МГНОВЕННЫЙ ПАРСИНГ ПРИ ПУШЕ ===
    // Делаем это синхронно и скроллим вверх, чтобы ты сразу увидел результат
    private void loadFromCacheOnly() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !isAdded()) return;
        
        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");
        
        if (!cachedJson.equals("[]") && cachedJson.length() > 5) {
            try {
                JSONArray array = new JSONArray(cachedJson);
                List<NotificationModels.NotificationItem> items = new ArrayList<>();
                boolean hasUnread = false;

                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String type = obj.optString("type", "time");
                    if (!obj.optBoolean("isRead", false)) hasUnread = true;

                    if ("time".equals(type)) {
                        items.add(new NotificationModels.TimeNotification(
                                obj.optString("mainText"), obj.optString("actionText"), obj.optLong("timestamp")
                        ));
                    } else if ("follower".equals(type)) {
                        items.add(new NotificationModels.FollowerNotification(
                                obj.optLong("timestamp"), obj.optString("uid"), obj.optString("nickname"),
                                obj.optString("photo"), obj.optBoolean("isFollowing", false)
                        ));
                    }
                }

                if (!items.isEmpty()) {
                    recycler.setVisibility(View.VISIBLE);
                    emptyText.setVisibility(View.GONE);
                    
                    // Жестко пересоздаем адаптер для гарантии обновления
                    adapter = new NotificationsAdapter(items, activity);
                    recycler.setAdapter(adapter);
                    
                    // ПРИНУДИТЕЛЬНО возвращаем тебя на самый верх списка!
                    recycler.scrollToPosition(0);

                    if (hasUnread) {
                        markAllAsRead(activity, array, cacheKey);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
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
            parseAndDisplayAsync(cachedJson, activity, false);
        } else {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            if (!swipeRefresh.isRefreshing()) loadingSpinner.setVisibility(View.VISIBLE);
        }

        if (activity.vpsToken == null) {
            if (!hasCache) showEmptyState();
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return; 
        }

        VpsApi.getNotificationsHistory(activity.vpsToken, new VpsApi.Callback() {
            @Override
            public void onSuccess(String result) {
                Utils.backgroundExecutor.execute(() -> {
                    try {
                        JSONArray serverArray = new JSONArray(result);
                        JSONArray localArray = new JSONArray(prefs.getString(cacheKey, "[]"));
                        JSONArray mergedArray = new JSONArray();
                        
                        for (int i = 0; i < serverArray.length(); i++) mergedArray.put(serverArray.getJSONObject(i));
                        
                        for (int i = 0; i < localArray.length(); i++) {
                            JSONObject localObj = localArray.getJSONObject(i);
                            if ("time".equals(localObj.optString("type"))) {
                                boolean found = false;
                                for (int j = 0; j < serverArray.length(); j++) {
                                    if (serverArray.getJSONObject(j).optLong("timestamp") == localObj.optLong("timestamp")) {
                                        found = true; break;
                                    }
                                }
                                if (!found) mergedArray.put(localObj); 
                            }
                        }
                        
                        List<JSONObject> list = new ArrayList<>();
                        for (int i = 0; i < mergedArray.length(); i++) list.add(mergedArray.getJSONObject(i));
                        Collections.sort(list, (a, b) -> Long.compare(b.optLong("timestamp"), a.optLong("timestamp")));
                        
                        JSONArray finalArray = new JSONArray();
                        for (JSONObject obj : list) finalArray.put(obj);
                        
                        String finalJson = finalArray.toString();
                        
                        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                        prefs.edit().putString(cacheKey, finalJson).commit();
                        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
                        
                        uiHandler.post(() -> {
                            if (isAdded()) {
                                loadingSpinner.setVisibility(View.GONE);
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                                parseAndDisplayAsync(finalJson, activity, false);
                            }
                        });
                    } catch (Exception e) {
                        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                        prefs.edit().putString(cacheKey, result).commit();
                        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
                        
                        uiHandler.post(() -> {
                            if (isAdded()) {
                                loadingSpinner.setVisibility(View.GONE);
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                                parseAndDisplayAsync(result, activity, false);
                            }
                        });
                    }
                });
            }

            @Override
            public void onError(String error) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    loadingSpinner.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    if (!hasCache) {
                        showEmptyState();
                        Toast.makeText(getContext(), getString(R.string.err_server) + " " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void parseAndDisplayAsync(String jsonResult, MainActivity activity, boolean forceRefresh) {
        Utils.backgroundExecutor.execute(() -> {
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
                
                final boolean finalHasUnread = hasUnread;

                uiHandler.post(() -> {
                    if (!isAdded()) return;

                    if (items.isEmpty()) {
                        showEmptyState();
                    } else {
                        recycler.setVisibility(View.VISIBLE);
                        emptyText.setVisibility(View.GONE);
                        
                        if (adapter == null || forceRefresh) {
                            adapter = new NotificationsAdapter(items, activity);
                            recycler.setAdapter(adapter);
                        } else {
                            adapter.updateItems(items);
                        }
                        
                        if (finalHasUnread) {
                            markAllAsRead(activity, array, getCacheKey());
                        }
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> { if (isAdded()) showEmptyState(); });
            }
        });
    }

    private void showEmptyState() {
        loadingSpinner.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void markAllAsRead(MainActivity activity, JSONArray array, String cacheKey) {
        Utils.backgroundExecutor.execute(() -> {
            try {
                for (int i = 0; i < array.length(); i++) {
                    array.getJSONObject(i).put("isRead", true);
                }
                if (isAdded()) {
                    SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                    prefs.edit().putString(cacheKey, array.toString()).apply();
                    prefs.registerOnSharedPreferenceChangeListener(prefsListener);
                }
            } catch (Exception ignored) {}
        });

        if (activity != null && activity.vpsToken != null) {
            VpsApi.markNotificationsRead(activity.vpsToken, new VpsApi.Callback() {
                @Override public void onSuccess(String result) {}
                @Override public void onError(String error) {}
            });
        }
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
        
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener);

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(pushReceiver, new android.content.IntentFilter("UPDATE_BADGE_BROADCAST"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(pushReceiver, new android.content.IntentFilter("UPDATE_BADGE_BROADCAST"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(pushReceiver, new android.content.IntentFilter("UPDATE_BADGE_BROADCAST"));
        }

        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
                };
            }
            if (getView() != null) getView().postDelayed(hideBgRunnable, 300);
            else activity.updateGlobalBackground(false);
            
            activity.updateNotificationBadge();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener);
            
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(pushReceiver);
        } catch (Exception ignored) {}
        
        try {
            requireContext().unregisterReceiver(pushReceiver);
        } catch (Exception ignored) {}
    }
}
