package com.mynewtime.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mynewtime.app.MainActivity;
import com.mynewtime.app.R;
import com.mynewtime.app.models.User;

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
        // Создаем физическую карточку (вызывается только пару раз для видимых элементов)
        View view = LayoutInflater.from(activity).inflate(R.layout.item_search_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        // Заполняем карточку данными (вызывается при быстром скроллинге)
        final User u = users.get(position);
        
        holder.name.setText(u.nickname != null ? u.nickname : activity.getString(R.string.new_user));
        holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon);

        // Пока используем твой старый метод. Потом заменим на Glide!
        if (u.photo != null && u.photo.length() > 10) {
            if (u.photo.startsWith("http")) {
                // Если это обычная ссылка с сервера (новое будущее!)
                Glide.with(activity).load(u.photo).circleCrop().into(holder.avatar);
            } else {
                // Если это старый Base64 (обратная совместимость)
                try {
                    byte[] imageByteArray = android.util.Base64.decode(u.photo, android.util.Base64.DEFAULT);
                    Glide.with(activity).asBitmap().load(imageByteArray).circleCrop().into(holder.avatar);
                } catch (Exception e) {}
            }
        }

        // Клик по карточке
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.navigator.switchScreen(4, u.id);
            }
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    // Тот самый ViewHolder, который теперь является обязательным стандартом
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

