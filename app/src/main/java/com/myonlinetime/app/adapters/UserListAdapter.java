package com.myonlinetime.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.UserViewHolder> {
    private List<User> users = new ArrayList<>();
    private MainActivity activity;

    public UserListAdapter(MainActivity activity) {
        this.activity = activity;
    }

    public void setUsers(List<User> newUsers) {
        this.users = newUsers != null ? newUsers : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(activity).inflate(R.layout.item_search_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        final User u = users.get(position);
        
        holder.name.setText(u.nickname != null ? u.nickname : activity.getString(R.string.new_user));
        holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon);

        // Уникальная метка для карточки, чтобы при быстрой прокрутке картинки не путались
        holder.avatar.setTag(u.uid);

        if (u.photo != null && u.photo.length() > 10) {
            if (u.photo.startsWith("http")) {
                Glide.with(activity).load(u.photo).circleCrop().into(holder.avatar);
            } else {
                // Асинхронная расшифровка тяжелого Base64 (спасает от вылетов и фризов)
                Utils.backgroundExecutor.execute(() -> {
                    try {
                        byte[] imageByteArray = android.util.Base64.decode(u.photo, android.util.Base64.DEFAULT);
                        
                        // Проверка на видео (магические байты MP4)
                        boolean isVideo = imageByteArray.length > 8 &&
                                          imageByteArray[4] == 0x66 && imageByteArray[5] == 0x74 &&
                                          imageByteArray[6] == 0x79 && imageByteArray[7] == 0x70;

                        activity.runOnUiThread(() -> {
                            // Отрисовываем, только если карточка не успела уехать при скролле
                            if (u.uid.equals(holder.avatar.getTag())) {
                                if (isVideo) {
                                    // В поиске для видео-аватарок ставим иконку (плейер в списке убьет телефон)
                                    holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon); 
                                } else {
                                    Glide.with(activity).load(imageByteArray).circleCrop().into(holder.avatar);
                                }
                            }
                        });
                    } catch (Exception e) {}
                });
            }
        }

        holder.itemView.setOnClickListener(v -> {
            activity.navigator.switchScreen(4, u.uid);
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.search_user_avatar);
            name = itemView.findViewById(R.id.search_user_name);
        }
    }
}
