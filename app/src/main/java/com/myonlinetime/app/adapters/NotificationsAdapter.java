package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.NotificationModels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<NotificationModels.NotificationItem> items = new ArrayList<>();
    private final MainActivity activity;
    private final SimpleDateFormat sdf;

    public NotificationsAdapter(List<NotificationModels.NotificationItem> initialItems, MainActivity activity) {
        this.items.addAll(initialItems);
        this.activity = activity;
        this.sdf = new SimpleDateFormat(activity.getString(R.string.date_format), Locale.getDefault());
        
        // === ВАЖНО: Включаем стабильные ID для плавной анимации и скролла ===
        setHasStableIds(true); 
    }

    public void updateItems(List<NotificationModels.NotificationItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getType();
    }

    // === Уникальный идентификатор для каждой карточки на основе времени ===
    @Override
    public long getItemId(int position) {
        NotificationModels.NotificationItem item = items.get(position);
        if (item instanceof NotificationModels.TimeNotification) {
            return ((NotificationModels.TimeNotification) item).timestamp;
        } else if (item instanceof NotificationModels.FollowerNotification) {
            return ((NotificationModels.FollowerNotification) item).timestamp;
        }
        return position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == NotificationModels.NotificationItem.TYPE_FOLLOWER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notif_follower, parent, false);
            return new FollowerViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notif_time, parent, false);
            return new TimeViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        NotificationModels.NotificationItem item = items.get(position);
        
        if (holder instanceof FollowerViewHolder) {
            ((FollowerViewHolder) holder).bind((NotificationModels.FollowerNotification) item, activity, sdf);
        } else if (holder instanceof TimeViewHolder) {
            ((TimeViewHolder) holder).bind((NotificationModels.TimeNotification) item, activity, sdf);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    // ==========================================
    // VIEWHOLDER ДЛЯ ВРЕМЕНИ
    // ==========================================
    static class TimeViewHolder extends RecyclerView.ViewHolder {
        TextView mainText, dateText;

        TimeViewHolder(View v) {
            super(v);
            mainText = v.findViewById(R.id.notif_text_main);
            dateText = v.findViewById(R.id.notif_date);
        }

        void bind(NotificationModels.TimeNotification item, MainActivity activity, SimpleDateFormat sdf) {
            mainText.setText(item.mainText);
            dateText.setText(sdf.format(new Date(item.timestamp)));

            itemView.setOnClickListener(v -> {
                if (activity != null) {
                    ImageView backBtn = activity.findViewById(R.id.header_back_btn);
                    if (backBtn != null) backBtn.performClick();
                    View navTime = activity.findViewById(R.id.nav_usage);
                    if (navTime != null) navTime.performClick();
                }
            });
        }
    }

    // ==========================================
    // VIEWHOLDER ДЛЯ ПОДПИСЧИКОВ
    // ==========================================
    static class FollowerViewHolder extends RecyclerView.ViewHolder {
        View followerUserCard;
        ImageView followerAvatar;
        TextView followerNickname, followerDate;
        Button btnFollow;

        FollowerViewHolder(View v) {
            super(v);
            followerUserCard = v.findViewById(R.id.follower_user_card);
            followerAvatar = v.findViewById(R.id.notif_user_avatar);
            followerNickname = v.findViewById(R.id.notif_user_nickname);
            followerDate = v.findViewById(R.id.notif_follower_date);
            btnFollow = v.findViewById(R.id.notif_btn_follow);
        }

        void bind(NotificationModels.FollowerNotification item, MainActivity activity, SimpleDateFormat sdf) {
            followerDate.setText(sdf.format(new Date(item.timestamp)));
            followerNickname.setText(item.nickname != null ? item.nickname : activity.getString(R.string.no_name));

            if (item.photo == null || item.photo.isEmpty() || item.photo.equals("null")) {
                Glide.with(activity).load(R.drawable.bg_edit_circle).circleCrop().into(followerAvatar);
            } else {
                Glide.with(activity).load(item.photo).circleCrop().error(R.drawable.bg_edit_circle).into(followerAvatar);
            }

            updateFollowButtonUI(btnFollow, item.isFollowing, activity);

            followerUserCard.setOnClickListener(v -> {
                if (activity != null) {
                    activity.navigator.openSubScreen(OtherProfileFragment.newInstance(
                            item.uid, activity.getString(R.string.title_notifications), item.nickname, "", item.photo
                    ));
                }
            });

            btnFollow.setOnClickListener(v -> {
                if (!btnFollow.isEnabled()) return;
                btnFollow.setEnabled(false);
                
                boolean nextStatus = !item.isFollowing;
                item.isFollowing = nextStatus;
                updateFollowButtonUI(btnFollow, nextStatus, activity);
                updateStatusInPrefs(item.uid, nextStatus, activity);

                if (activity.vpsToken != null) {
                    VpsApi.setFollow(activity.vpsToken, item.uid, nextStatus, new VpsApi.Callback() {
                        @Override public void onSuccess(String s) {
                            activity.runOnUiThread(() -> btnFollow.setEnabled(true));
                        }
                        @Override public void onError(String err) {
                            activity.runOnUiThread(() -> {
                                btnFollow.setEnabled(true);
                                item.isFollowing = !nextStatus;
                                updateFollowButtonUI(btnFollow, item.isFollowing, activity);
                                updateStatusInPrefs(item.uid, item.isFollowing, activity);
                                Toast.makeText(activity, activity.getString(R.string.err_server) + " " + err, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                }
            });
        }

        private void updateStatusInPrefs(String uid, boolean isFollowing, MainActivity activity) {
            SharedPreferences prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            try {
                JSONArray array = new JSONArray(prefs.getString("notif_history_array", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if ("follower".equals(obj.optString("type")) && uid.equals(obj.optString("uid"))) {
                        obj.put("isFollowing", isFollowing);
                    }
                }
                prefs.edit().putString("notif_history_array", array.toString()).apply();
            } catch (Exception e) {}
        }

        private void updateFollowButtonUI(Button btn, boolean isFollowing, MainActivity activity) {
            if (isFollowing) {
                btn.setText(R.string.btn_unfollow);
                btn.setTextColor(ContextCompat.getColor(activity, R.color.textGrayDynamic));
                btn.setBackgroundResource(R.drawable.bg_button_gray);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    btn.setForeground(ContextCompat.getDrawable(activity, R.drawable.ripple_button_gray));
                }
            } else {
                btn.setText(R.string.btn_follow);
                btn.setTextColor(ContextCompat.getColor(activity, R.color.textWhiteStatic));
                btn.setBackgroundResource(R.drawable.bg_button_grapefruit);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    btn.setForeground(ContextCompat.getDrawable(activity, R.drawable.ripple_button_grapefruit));
                }
            }
        }
    }
}
