package com.myonlinetime.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.ui.OtherProfileFragment; // Импортируем фрагмент для предзагрузки

import java.util.ArrayList;
import java.util.List;

public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.UserViewHolder> {
    private List<User> users = new ArrayList<>();
    private MainActivity activity;
    private OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    public UserListAdapter(MainActivity activity, OnUserClickListener listener) {
        this.activity = activity;
        this.listener = listener;
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

        // Очищаем старую картинку, чтобы при быстром скролле не было "морганий" чужих аватарок
        Glide.with(activity).clear(holder.avatar);

        if (u.photo != null && u.photo.startsWith("http")) {
            Glide.with(activity)
                 .load(u.photo)
                 .circleCrop()
                 .placeholder(android.R.drawable.sym_def_app_icon) 
                 .into(holder.avatar);
        } else {
            holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        // === ИНТЕГРАЦИЯ TELEGRAM-ЭФФЕКТА ===
        // Вызывается ТОЛЬКО для видимых на экране карточек. Старые данные автоматически 
        // вытесняются из LruCache, спасая память устройства.
        if (activity.vpsToken != null && !activity.vpsToken.isEmpty()) {
            OtherProfileFragment.prefetchProfile(activity.vpsToken, u.uid);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onUserClick(u);
            }
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
