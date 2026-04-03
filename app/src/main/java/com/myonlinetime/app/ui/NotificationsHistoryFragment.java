package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.Intent;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationsHistoryFragment extends Fragment {

    // === ТЕХНИЧЕСКИЙ ХАРДКОР ВЫНЕСЕН В КОНСТАНТЫ ===
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_HISTORY = "notif_history_array";
    private static final String JSON_MAIN_TEXT = "mainText";
    private static final String JSON_ACTION_TEXT = "actionText";
    private static final String JSON_TIMESTAMP = "timestamp";
    private static final String JSON_IS_READ = "isRead";
    private static final String EXTRA_OPEN_TAB = "open_tab";
    private static final String TAB_TIME = "time";

    private Runnable hideBgRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        RecyclerView recycler = view.findViewById(R.id.recycler_notifications);
        TextView emptyText = view.findViewById(R.id.empty_notif_text);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String historyJson = prefs.getString(KEY_HISTORY, "[]");
        
        List<NotifItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(historyJson);
            for (int i = array.length() - 1; i >= 0; i--) {
                JSONObject obj = array.getJSONObject(i);
                items.add(new NotifItem(
                        obj.getString(JSON_MAIN_TEXT),
                        obj.getString(JSON_ACTION_TEXT),
                        obj.getLong(JSON_TIMESTAMP)
                ));
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (items.isEmpty()) {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            recycler.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            recycler.setLayoutManager(new LinearLayoutManager(getContext()));
            recycler.setAdapter(new NotifAdapter(items, (MainActivity) getActivity()));
            
            markAllAsRead();
        }

        return view;
    }

    private void markAllAsRead() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String historyJson = prefs.getString(KEY_HISTORY, "[]");
        
        try {
            JSONArray array = new JSONArray(historyJson);
            if (array.length() == 0) return;

            for (int i = 0; i < array.length(); i++) {
                array.getJSONObject(i).put(JSON_IS_READ, true);
            }
            
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
            
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).updateNotificationBadge();
            }
        } catch (Exception e) { e.printStackTrace(); }
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
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.resetHeader(); 
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
                    if (isAdded() && !isHidden()) {
                        activity.updateGlobalBackground(false);
                    }
                };
            }
            if (getView() != null) {
                getView().postDelayed(hideBgRunnable, 300);
            } else {
                activity.updateGlobalBackground(false);
            }
        } else {
            if (hideBgRunnable != null && getView() != null) {
                getView().removeCallbacks(hideBgRunnable);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (hideBgRunnable == null) {
                hideBgRunnable = () -> {
                    if (isAdded() && !isHidden()) {
                        activity.updateGlobalBackground(false);
                    }
                };
            }
            if (getView() != null) {
                getView().postDelayed(hideBgRunnable, 300);
            } else {
                activity.updateGlobalBackground(false);
            }
        }
    }

    private static class NotifItem {
        String mainText; String actionText; long timestamp;
        NotifItem(String m, String a, long t) { 
            mainText = m; actionText = a; timestamp = t; 
        }
    }

    private static class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.ViewHolder> {
        private final List<NotifItem> items;
        private final MainActivity activity;
        private final SimpleDateFormat sdf;

        NotifAdapter(List<NotifItem> items, MainActivity activity) {
            this.items = items;
            this.activity = activity;
            // Перенос формата даты в ресурсы
            this.sdf = new SimpleDateFormat(activity.getString(R.string.date_format), Locale.getDefault());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification_card, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            NotifItem item = items.get(position);
            holder.mainText.setText(item.mainText);
            
            // ========================================================
            // ИСПРАВЛЕНИЕ ПЕРЕВОДА: Убрали перезапись текста из JSON!
            // Теперь работает строка @string/action_view из XML-разметки
            // holder.actionText.setText(item.actionText); // <-- УДАЛЕНО
            // ========================================================
            
            holder.dateText.setText(sdf.format(new Date(item.timestamp)));

            holder.actionText.setOnClickListener(v -> {
                if (activity != null) {
                    Intent intent = new Intent(activity, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra(EXTRA_OPEN_TAB, TAB_TIME);
                    activity.startActivity(intent);
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView mainText, actionText, dateText;
            ViewHolder(View v) {
                super(v);
                mainText = v.findViewById(R.id.notif_text_main);
                actionText = v.findViewById(R.id.notif_text_action);
                dateText = v.findViewById(R.id.notif_date);
            }
        }
    }
}
