package com.myonlinetime.app.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
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
    private SwipeRefreshLayout swipeRefresh; 
    private TextView emptyText;
    private ProgressBar loadingSpinner;
    private NotificationsAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isReceiverRegistered = false;
    
    // Флаг для предотвращения рывков во время анимации
    private boolean isTransitioning = false;

    // Срабатывает, когда кэш поменялся 
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sharedPrefs, key) -> {
        if (key != null && key.equals(getCacheKey())) {
            uiHandler.post(() -> {
                if (isAdded() && !isHidden()) {
                    loadFromCacheOnly();
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) activity.updateNotificationBadge();
                }
            });
        }
    };

    // === ЖИВЫЕ УВЕДОМЛЕНИЯ ===
    private final BroadcastReceiver pushReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("UPDATE_BADGE_BROADCAST".equals(intent.getAction())) {
                uiHandler.post(() -> {
                    if (isAdded() && !isHidden()) {
                        // Тихо запрашиваем сервер для свежих статусов подписки (isFollowing)
                        loadHistory(false, 0); 
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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceivers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceivers();
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

        swipeRefresh = new SwipeRefreshLayout(requireContext());
        ViewGroup.LayoutParams recyclerOriginalParams = recycler.getLayoutParams();
        swipeRefresh.setLayoutParams(recyclerOriginalParams); 
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.grapefruit)); 
        
        // === Сдвигаем тянучку из-под шапки вниз ===
        int startOffset = (int) (70 * getResources().getDisplayMetrics().density); 
        int endOffset = (int) (110 * getResources().getDisplayMetrics().density); 
        swipeRefresh.setProgressViewOffset(false, startOffset, endOffset);

        ViewGroup parent = (ViewGroup) recycler.getParent();
        int index = parent.indexOfChild(recycler);
        parent.removeView(recycler);
        
        recycler.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeRefresh.addView(recycler);
        parent.addView(swipeRefresh, index);

        swipeRefresh.setOnRefreshListener(() -> {
            loadHistory(true, System.currentTimeMillis());
        });

        // ИСПРАВЛЕНИЕ: Защита от рывков при первом открытии
        isTransitioning = true;
        uiHandler.postDelayed(() -> isTransitioning = false, 400); // 400 мс - время анимации
        
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
        loadHistory(false, 0);

        return view;
    }

    private void loadFromCacheOnly() {
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) return;

        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !isAdded()) return;
        
        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");
        
        if (!cachedJson.equals("[]") && cachedJson.length() > 5) {
            Utils.backgroundExecutor.execute(() -> {
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

                    final boolean finalHasUnread = hasUnread;

                    // Ожидаем завершения анимации, если она еще идет
                    long delay = isTransitioning ? 400 : 0;

                    uiHandler.postDelayed(() -> {
                        if (!isAdded()) return;

                        if (!items.isEmpty()) {
                            recycler.setVisibility(View.VISIBLE);
                            emptyText.setVisibility(View.GONE);
                            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                            
                            if (adapter == null) {
                                adapter = new NotificationsAdapter(items, activity);
                                recycler.setAdapter(adapter);
                            } else {
                                adapter.updateItems(items);
                            }
                            
                            // Предзагрузка удалена для экономии трафика

                            if (finalHasUnread) {
                                markAllAsRead(activity, array, cacheKey);
                            }
                        }
                    }, delay);
                } catch (Exception e) { e.printStackTrace(); }
            });
        }
    }

    private void loadHistory(boolean isSwipeRefresh, long swipeStartTime) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");
        
        boolean hasCache = !cachedJson.equals("[]") && cachedJson.length() > 5;
        
        if (hasCache) {
            // ИСПРАВЛЕНИЕ: Не скрываем спиннер здесь! Он скроется сам в parseAndDisplayAsync, 
            // когда пройдет задержка анимации. Иначе будет черный экран на 400мс.
            if (!isSwipeRefresh) {
                parseAndDisplayAsync(cachedJson, activity, 0, false); 
            }
        } else {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            if (!isSwipeRefresh && loadingSpinner != null) {
                loadingSpinner.setVisibility(View.VISIBLE);
            }
        }

        if (activity.vpsToken == null) {
            if (!hasCache) showEmptyState();
            uiHandler.postDelayed(() -> { 
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false); 
            }, 2000); 
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
                        
                        long delayBeforeStop = 0;
                        if (isSwipeRefresh) {
                            long elapsed = System.currentTimeMillis() - swipeStartTime;
                            delayBeforeStop = Math.max(0, 2000 - elapsed);
                        }
                        
                        parseAndDisplayAsync(finalJson, activity, delayBeforeStop, isSwipeRefresh);

                    } catch (Exception e) {
                        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                        prefs.edit().putString(cacheKey, result).commit();
                        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
                        
                        long delayBeforeStop = 0;
                        if (isSwipeRefresh) {
                            long elapsed = System.currentTimeMillis() - swipeStartTime;
                            delayBeforeStop = Math.max(0, 2000 - elapsed);
                        }
                        
                        parseAndDisplayAsync(result, activity, delayBeforeStop, isSwipeRefresh);
                    }
                });
            }

            @Override
            public void onError(String error) {
                long delayBeforeStop = 0;
                if (isSwipeRefresh) {
                    long elapsed = System.currentTimeMillis() - swipeStartTime;
                    delayBeforeStop = Math.max(0, 2000 - elapsed);
                }
                
                uiHandler.postDelayed(() -> {
                    if (!isAdded()) return;
                    if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    if (!hasCache) {
                        showEmptyState();
                        Toast.makeText(getContext(), getString(R.string.err_server) + " " + error, Toast.LENGTH_SHORT).show();
                    }
                }, delayBeforeStop);
            }
        });
    }

    private void parseAndDisplayAsync(String jsonResult, MainActivity activity, long delayStopSpinner, boolean isFromSwipe) {
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

                // ИСПРАВЛЕНИЕ: Ждем завершения анимации, если она еще не закончилась
                long baseDelay = delayStopSpinner;
                if (isTransitioning) {
                    baseDelay = Math.max(baseDelay, 400); // Принудительно откладываем рендер
                }
                final long finalDelay = baseDelay;

                uiHandler.postDelayed(() -> {
                    if (!isAdded()) return;

                    if (!isFromSwipe && swipeRefresh != null && swipeRefresh.isRefreshing()) {
                        return;
                    }

                    if (isFromSwipe && swipeRefresh != null) {
                        swipeRefresh.setRefreshing(false);
                    }
                    if (!isFromSwipe && loadingSpinner != null) {
                        loadingSpinner.setVisibility(View.GONE);
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
                        
                        // Предзагрузка удалена
                        
                        if (finalHasUnread) {
                            markAllAsRead(activity, array, getCacheKey());
                        }
                    }
                }, finalDelay);

            } catch (Exception e) {
                uiHandler.postDelayed(() -> { 
                    if (isAdded()) {
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        showEmptyState();
                    }
                }, delayStopSpinner);
            }
        });
    }

    private void showEmptyState() {
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
        // Не скрываем recycler, чтобы тянучка не ломалась!
        if (adapter != null) adapter.updateItems(new ArrayList<>());
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

    private void registerReceivers() {
        if (isReceiverRegistered || getContext() == null) return;
        
        try {
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);

            LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(pushReceiver, new IntentFilter("UPDATE_BADGE_BROADCAST"));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(pushReceiver, new IntentFilter("UPDATE_BADGE_BROADCAST"), Context.RECEIVER_EXPORTED);
            } else {
                requireContext().registerReceiver(pushReceiver, new IntentFilter("UPDATE_BADGE_BROADCAST"));
            }
            isReceiverRegistered = true;
        } catch (Exception e) {}
    }

    private void unregisterReceivers() {
        if (!isReceiverRegistered || getContext() == null) return;
        
        try {
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        } catch (Exception e) {}
            
        try { LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(pushReceiver); } catch (Exception e) {}
        try { requireContext().unregisterReceiver(pushReceiver); } catch (Exception e) {}
        
        isReceiverRegistered = false;
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
            
            // ИСПРАВЛЕНИЕ: Защита от рывков при возврате на экран
            isTransitioning = true;
            uiHandler.postDelayed(() -> isTransitioning = false, 400);
            
            if (loadingSpinner != null && recycler.getVisibility() != View.VISIBLE) {
                loadingSpinner.setVisibility(View.VISIBLE);
            }
            loadHistory(false, 0); 
            
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
            
            activity.updateNotificationBadge();
        }
    }
}
