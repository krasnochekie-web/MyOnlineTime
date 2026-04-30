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

import java.util.ArrayList;
import java.util.List;

// Наследуемся от современного RecyclerView
public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.UserViewHolder> {
    private List<User> users = new ArrayList<>();
    private MainActivity activity;

    public UserListAdapter(MainActivity activity) {
        this.activity = activity;
    }

    public void setUsers(List<User> newUsers) {
        this.users = newUsers != null ? newUsers : new ArrayList<User>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Создаем физическую карточку
        View view = LayoutInflater.from(activity).inflate(R.layout.item_search_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        // Заполняем карточку данными
        final User u = users.get(position);
        
        holder.name.setText(u.nickname != null ? u.nickname : activity.getString(R.string.new_user));
        holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon);

        // Загрузка аватарки с встроенной поддержкой GIF (убрали .asBitmap())
        if (u.photo != null && u.photo.length() > 10) {
            if (u.photo.startsWith("http")) {
                // Обычная ссылка
                Glide.with(activity).load(u.photo).circleCrop().into(holder.avatar);
            } else {
                // Base64 (теперь поддерживает GIF анимацию)
                try {
                    byte[] imageByteArray = android.util.Base64.decode(u.photo, android.util.Base64.DEFAULT);
                    Glide.with(activity).load(imageByteArray).circleCrop().into(holder.avatar);
                } catch (Exception e) {}
            }
        }

        // Клик по карточке
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ИСПРАВЛЕНИЕ: Передаем именно UID пользователя, а не внутренний id.
                // Теперь фрагмент профиля точно поймет, чью страницу нужно загрузить!
                activity.navigator.switchScreen(4, u.uid);
            }
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    // Тот самый ViewHolder
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
