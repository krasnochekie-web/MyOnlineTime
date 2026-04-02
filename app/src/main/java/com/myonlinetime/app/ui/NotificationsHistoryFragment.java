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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_notifications_history, container, false);

        setupHeader();

        RecyclerView recycler = view.findViewById(R.id.recycler_notifications);
        TextView emptyText = view.findViewById(R.id.empty_notif_text);

        // Читаем историю
        SharedPreferences prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        String historyJson = prefs.getString("notif_history_array", "[]");
        
        List<NotifItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(historyJson);
            // Идем с конца, чтобы свежие были сверху
            for (int i = array.length() - 1; i >= 0; i--) {
                JSONObject obj = array.getJSONObject(i);
                items.add(new NotifItem(
                        obj.getString("mainText"),
                        obj.getString("actionText"),
                        obj.getLong("timestamp")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (items.isEmpty()) {
            recycler.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            recycler.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            recycler.setLayoutManager(new LinearLayoutManager(getContext()));
            recycler.setAdapter(new NotifAdapter(items, (MainActivity) getActivity()));
        }

        return view;
    }

    private void setupHeader() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(getString(R.string.title_notifications));
            
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 

            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) {
                bellBtn.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.resetHeader(); 
        }
    }

    // ИСПРАВЛЕНИЕ: Метод onResume с жестким выключением фона удален!

    // --- АДАПТЕР ДЛЯ СПИСКА ---
    private static class NotifItem {
        String mainText; String actionText; long timestamp;
        NotifItem(String m, String a, long t) { mainText = m; actionText = a; timestamp = t; }
    }

    private static class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.ViewHolder> {
        private final List<NotifItem> items;
        private final MainActivity activity;
        private final SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

        NotifAdapter(List<NotifItem> items, MainActivity activity) {
            this.items = items;
            this.activity = activity;
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
            holder.actionText.setText(item.actionText);
            holder.dateText.setText(sdf.format(new Date(item.timestamp)));

            // Делаем кликабельным текст "Нажмите, чтобы просмотреть"
            holder.actionText.setOnClickListener(v -> {
                if (activity != null) {
                    Intent intent = new Intent(activity, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("open_tab", "time");
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
