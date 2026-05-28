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
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NotificationsHistoryFragment extends Fragment {

    private static final String PREFS_NAME = "AppPrefs";

    public static final long MAX_PRELOAD_BG_BYTES = 1024L * 1024L;

    private static final int EAGER_TOP_K = 5;

    // Окно контентного дедупа time-уведомлений (если сервер ещё не отдаёт clientId).
    private static final long TIME_DEDUP_WINDOW_MS = 60L * 60L * 1000L; // 1 час

    private Runnable hideBgRunnable;
    private RecyclerView recycler;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;
    private ProgressBar loadingSpinner;
    private NotificationsAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isReceiverRegistered = false;

    private boolean isTransitioning = false;

    private List<NotificationModels.NotificationItem> currentItems = new ArrayList<>();

    private RecyclerView.OnChildAttachStateChangeListener attachListener;

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

    private final BroadcastReceiver pushReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("UPDATE_BADGE_BROADCAST".equals(intent.getAction())) {
                uiHandler.post(() -> {
                    if (isAdded() && !isHidden()) {
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

    // === Дедуп локального time-уведомления против серверного списка ===
    // 1) точный clientId; 2) текст + близкое время; 3) legacy точный timestamp.
    private static boolean serverHasTimeItem(JSONArray serverArray, JSONObject localObj) throws org.json.JSONException {
        String lClientId = localObj.optString("clientId", "");
        long lts = localObj.optLong("timestamp");
        String lMain = localObj.optString("mainText");
        for (int j = 0; j < serverArray.length(); j++) {
            JSONObject s = serverArray.getJSONObject(j);

            if (!lClientId.isEmpty() && lClientId.equals(s.optString("clientId", ""))) return true;

            if (!"time".equals(s.optString("type", "time"))) continue;

            if (lMain.equals(s.optString("mainText"))
                    && Math.abs(s.optLong("timestamp") - lts) <= TIME_DEDUP_WINDOW_MS) return true;

            if (s.optLong("timestamp") == lts) return true;
        }
        return false;
    }

    // Гасит глобальный фон НЕМЕДЛЕННО, отменив отложенный hide.
    // Это убирает однократное мерцание кастомного фона при заходе на экран
    // уведомлений сразу после профиля (фон не успевает "моргнуть").
    private void hideGlobalBackgroundNow() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        if (getView() != null && hideBgRunnable != null) getView().removeCallbacks(hideBgRunnable);
        if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
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

        attachListener = new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                if (recycler == null) return;
                int pos = recycler.getChildAdapterPosition(view);
                if (pos < 0 || pos >= currentItems.size()) return;

                NotificationModels.NotificationItem item = currentItems.get(pos);
                if (!(item instanceof NotificationModels.FollowerNotification)) return;
                NotificationModels.FollowerNotification fn =
                        (NotificationModels.FollowerNotification) item;
                if (fn.uid == null || fn.uid.isEmpty()) return;

                MainActivity act = (MainActivity) getActivity();
                if (act == null || act.vpsToken == null || act.vpsToken.isEmpty()) return;

                OtherProfileFragment.prefetchProfile(act.vpsToken, fn.uid);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        };
        recycler.addOnChildAttachStateChangeListener(attachListener);

        isTransitioning = true;
        uiHandler.postDelayed(() -> isTransitioning = false, 400);

        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);

        prefetchFromCacheJsonAsync();

        loadHistory(false, 0);

        return view;
    }

    private void prefetchFromCacheJsonAsync() {
        if (getContext() == null) return;
        final String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String cachedJson = prefs.getString(cacheKey, "[]");
        if (cachedJson == null || cachedJson.length() <= 5 || cachedJson.equals("[]")) return;

        MainActivity act = (MainActivity) getActivity();
        final String token = act != null ? act.vpsToken : null;

        Utils.backgroundExecutor.execute(() -> {
            try {
                JSONArray array = new JSONArray(cachedJson);
                List<User> usersToCache = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (!"follower".equals(obj.optString("type", "time"))) continue;
                    User u = new User();
                    u.uid = obj.optString("uid");
                    u.nickname = obj.optString("nickname");
                    u.photo = obj.optString("photo");
                    u.isFollowing = obj.optBoolean("isFollowing", false);
                    if (u.uid != null && !u.uid.isEmpty()) usersToCache.add(u);
                }
                prefetchProfiles(usersToCache);
                eagerPrefetchTopK(token, usersToCache, EAGER_TOP_K);
            } catch (Exception ignored) {}
        });
    }

    private void prefetchProfiles(List<User> users) {
        if (users == null || users.isEmpty()) return;

        for (User u : users) {
            if (u == null || u.uid == null || u.uid.isEmpty()) continue;

            User existing = OtherProfileFragment.prefetchUserCache.get(u.uid);
            if (existing == null) {
                OtherProfileFragment.prefetchUserCache.put(u.uid, u);
            } else {
                if ((existing.nickname == null || existing.nickname.isEmpty()) && u.nickname != null) {
                    existing.nickname = u.nickname;
                }
                if ((existing.photo == null || existing.photo.isEmpty()) && u.photo != null) {
                    existing.photo = u.photo;
                }
            }
            OtherProfileFragment.prefetchFollowCache.put(u.uid, u.isFollowing);
        }
    }

    private void eagerPrefetchTopK(String token, List<User> users, int k) {
        if (token == null || token.isEmpty()) return;
        if (users == null || users.isEmpty() || k <= 0) return;

        int max = Math.min(k, users.size());
        for (int i = 0; i < max; i++) {
            User u = users.get(i);
            if (u == null || u.uid == null || u.uid.isEmpty()) continue;
            OtherProfileFragment.prefetchProfile(token, u.uid);
        }
    }

    private void loadFromCacheOnly() {
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) return;

        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !isAdded()) return;

        String cacheKey = getCacheKey();
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedJson = prefs.getString(cacheKey, "[]");

        if (!cachedJson.equals("[]") && cachedJson.length() > 5) {
            final String token = activity.vpsToken;
            Utils.backgroundExecutor.execute(() -> {
                try {
                    JSONArray array = new JSONArray(cachedJson);
                    List<NotificationModels.NotificationItem> items = new ArrayList<>();
                    List<User> usersToCache = new ArrayList<>();
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

                            User u = new User();
                            u.uid = obj.optString("uid");
                            u.nickname = obj.optString("nickname");
                            u.photo = obj.optString("photo");
                            u.isFollowing = obj.optBoolean("isFollowing", false);
                            usersToCache.add(u);
                        }
                    }

                    prefetchProfiles(usersToCache);
                    eagerPrefetchTopK(token, usersToCache, EAGER_TOP_K);

                    final boolean finalHasUnread = hasUnread;

                    long delay = isTransitioning ? 400 : 0;

                    uiHandler.postDelayed(() -> {
                        if (!isAdded()) return;

                        if (!items.isEmpty()) {
                            recycler.setVisibility(View.VISIBLE);
                            emptyText.setVisibility(View.GONE);
                            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);

                            currentItems = items;

                            if (adapter == null) {
                                adapter = new NotificationsAdapter(items, activity);
                                recycler.setAdapter(adapter);
                            } else {
                                adapter.updateItems(items);
                            }

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

                        // Локальные time-уведомления, которых ещё нет на сервере — оставляем.
                        // Дедуп по clientId, затем по контенту+времени, затем по timestamp.
                        for (int i = 0; i < localArray.length(); i++) {
                            JSONObject localObj = localArray.getJSONObject(i);
                            if ("time".equals(localObj.optString("type"))) {
                                if (!serverHasTimeItem(serverArray, localObj)) {
                                    mergedArray.put(localObj);
                                }
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
        final String token = activity != null ? activity.vpsToken : null;

        Utils.backgroundExecutor.execute(() -> {
            try {
                JSONArray array = new JSONArray(jsonResult);
                List<NotificationModels.NotificationItem> items = new ArrayList<>();
                List<User> usersToCache = new ArrayList<>();
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

                        User u = new User();
                        u.uid = obj.optString("uid");
                        u.nickname = obj.optString("nickname");
                        u.photo = obj.optString("photo");
                        u.isFollowing = obj.optBoolean("isFollowing", false);
                        usersToCache.add(u);
                    }
                }

                prefetchProfiles(usersToCache);
                eagerPrefetchTopK(token, usersToCache, EAGER_TOP_K);

                final boolean finalHasUnread = hasUnread;

                long baseDelay = delayStopSpinner;
                if (isTransitioning) {
                    baseDelay = Math.max(baseDelay, 400);
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

                        currentItems = items;

                        if (adapter == null) {
                            adapter = new NotificationsAdapter(items, activity);
                            recycler.setAdapter(adapter);
                        } else {
                            adapter.updateItems(items);
                        }

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
        currentItems = new ArrayList<>();
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

        if (recycler != null && attachListener != null) {
            recycler.removeOnChildAttachStateChangeListener(attachListener);
        }
        attachListener = null;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        if (!hidden) {
            setupHeader();

            isTransitioning = true;
            uiHandler.postDelayed(() -> isTransitioning = false, 400);

            if (loadingSpinner != null && recycler.getVisibility() != View.VISIBLE) {
                loadingSpinner.setVisibility(View.VISIBLE);
            }

            prefetchFromCacheJsonAsync();

            loadHistory(false, 0);

            // ИСПРАВЛЕНИЕ МЕРЦАНИЯ: гасим фон сразу, без 300мс-окна,
            // в которое успевал прорисоваться фон профиля.
            hideGlobalBackgroundNow();
        } else {
            if (hideBgRunnable != null && getView() != null) getView().removeCallbacks(hideBgRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();

            // ИСПРАВЛЕНИЕ МЕРЦАНИЯ: немедленное скрытие фона.
            hideGlobalBackgroundNow();

            activity.updateNotificationBadge();
        }
    }
}
