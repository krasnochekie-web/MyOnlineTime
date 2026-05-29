package com.myonlinetime.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.ui.OtherProfileFragment;

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

    // === Текущий uid (для блокировки перехода на свой профиль) ===
    private String getMyUid() {
        if (activity == null) return null;
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        return account != null ? account.getId() : null;
    }

    private boolean isMyUid(String uid) {
        if (uid == null || uid.isEmpty()) return false;
        String myUid = getMyUid();
        return myUid != null && myUid.equals(uid);
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

        holder.itemView.setOnClickListener(v -> {
            // Клик по СВОЕМУ профилю в списке — игнорируем (ничего не происходит).
            if (isMyUid(u.uid)) return;

            if (listener != null) {
                listener.onUserClick(u);
            }
        });
    }

    /**
     * Ленивый префетч фона: только для прикреплённых к экрану строк
     * (видимые + RV-prefetch ~3 шт). Тяжёлая bulk-загрузка
     * preloadBackgrounds(users) из фрагментов теперь не нужна.
     */
    @Override
    public void onViewAttachedToWindow(@NonNull UserViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        int pos = holder.getBindingAdapterPosition();
        if (pos < 0 || pos >= users.size()) return;
        User u = users.get(pos);
        if (u == null || u.background == null || !u.background.startsWith("http")) return;

        if (OtherProfileFragment.prefetchBgBytesCache.get(u.background) != null) return;

        List<User> one = new ArrayList<>(1);
        one.add(u);
        OtherProfileFragment.preloadBackgrounds(one);
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
