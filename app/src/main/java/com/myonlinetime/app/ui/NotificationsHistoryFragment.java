package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
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
    private NotificationsAdapter adapter;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        if (KEY_HISTORY.equals(key) && isAdded()) {
            loadHistory(); 
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        recycler = view.findViewById(R.id.recycler_notifications);
        emptyText = view.findViewById(R.id.empty_notif_text);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        loadHistory();

        return view;
    }

    private void loadHistory() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String historyJson = prefs.getString(KEY_HISTORY, "[]");
        
        List<NotificationModels.NotificationItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(historyJson);
            for (int i = array.length() - 1; i >= 0; i--) {
                JSONObject obj = array.getJSONObject(i);
                String type = obj.optString("type", "time"); 
                
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
        } catch (Exception e) { e.printStackTrace(); }

        if (items.isEmpty()) {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            recycler.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            
            if (adapter == null) {
                adapter = new NotificationsAdapter(items, (MainActivity) getActivity());
                recycler.setAdapter(adapter);
            } else {
                adapter.updateItems(items);
            }
            markAllAsRead();
        }
    }

    private void markAllAsRead() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String historyJson = prefs.getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(historyJson);
            if (array.length() == 0) return;

            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (!obj.optBoolean("isRead", false)) {
                    obj.put("isRead", true);
                    changed = true;
                }
            }
            if (changed) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
                prefs.registerOnSharedPreferenceChangeListener(prefsListener);
                
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).updateNotificationBadge();
                }
            }
        } catch (Exception e) {}
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

    @Override
    public void onPause() {
        super.onPause();
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
    }
}
