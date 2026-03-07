package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.utils.StatsHelper;
import com.myonlinetime.app.utils.Utils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ProfileFragment extends Fragment {

    // --- ЗАГЛУШКИ ДЛЯ БАЗЫ ДАННЫХ (Потом заменишь на вызовы API VpsApi) ---
    private Set<String> mockHiddenApps = new HashSet<>();
    private Map<String, String> mockDescriptions = new HashMap<>();
    // ----------------------------------------------------------------------

    public static ProfileFragment newInstance(String targetUid) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_UID", targetUid);
        fragment.setArguments(args);
        return fragment;
    }

    public ProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.layout_profile, container, false);
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return view;

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.resetHeader();

        String targetUid = "";
        if (getArguments() != null) {
            targetUid = getArguments().getString("TARGET_UID");
        }

        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account == null) return view;

        final String myUid = account.getId();
        final boolean isMe = targetUid.equals(myUid);
        final String finalTargetUid = targetUid;

        final TextView nameView = view.findViewById(R.id.profile_name);
        final TextView aboutView = view.findViewById(R.id.profile_about);
        final ImageView avatarView = view.findViewById(R.id.profile_avatar);
        final View btnEdit = view.findViewById(R.id.btn_edit_profile);
        final Button btnFollow = view.findViewById(R.id.btn_follow);
        final ImageView btnBack = view.findViewById(R.id.profile_back_btn);
        final TextView followersCount = view.findViewById(R.id.txt_followers_count);
        final TextView followingCount = view.findViewById(R.id.txt_following_count);
        final TextView weekTimeText = view.findViewById(R.id.profile_week_time);
        View followersClick = view.findViewById(R.id.container_followers);
        View followingClick = view.findViewById(R.id.container_following);

        TextView tabTopApps = view.findViewById(R.id.tab_top_apps);
        if (tabTopApps != null) tabTopApps.setSelected(true);

        final ImageView btnExpand = view.findViewById(R.id.btn_expand_apps);
        final ImageView btnCollapse = view.findViewById(R.id.btn_collapse_apps);
        final LinearLayout appsContainerLocal = view.findViewById(R.id.profile_apps_container);

        btnExpand.setOnClickListener(v -> {
            for (int i = 0; i < appsContainerLocal.getChildCount(); i++) {
                appsContainerLocal.getChildAt(i).setVisibility(View.VISIBLE);
            }
            btnExpand.setVisibility(View.GONE);
            btnCollapse.setVisibility(View.VISIBLE);
        });

        btnCollapse.setOnClickListener(v -> {
            for (int i = 3; i < appsContainerLocal.getChildCount(); i++) {
                appsContainerLocal.getChildAt(i).setVisibility(View.GONE);
            }
            btnCollapse.setVisibility(View.GONE);
            btnExpand.setVisibility(View.VISIBLE);
        });

        followersClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, true)));
        followingClick.setOnClickListener(v -> activity.navigator.openSubScreen(FollowsFragment.newInstance(finalTargetUid, false)));

        if (!isMe) btnBack.setVisibility(View.VISIBLE);

        if (isMe) {
            nameView.setText(activity.prefs.getString("my_nickname", account.getDisplayName()));
            aboutView.setText(activity.prefs.getString("my_about", ""));

            Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + myUid);
            if (cachedAvatar != null) {
                Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarView);
            } else {
                File file = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                if (file.exists()) {
                    Glide.with(activity).load(file).circleCrop().into(avatarView);
                } else {
                    avatarView.setBackgroundResource(R.drawable.bg_edit_circle);
                }
            }

            btnEdit.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);

            btnEdit.setOnClickListener(v -> activity.navigator.openSubScreen(EditProfileFragment.newInstance(
                    nameView.getText().toString(),
                    aboutView.getText().toString()
            )));

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                LinearLayout appsContainer = view.findViewById(R.id.profile_apps_container);
                // ВАЖНО: Тут вызывается твой StatsHelper для владельца!
                // Туда тоже нужно будет передать логику кнопок и меню.
                StatsHelper.loadStatsToProfile(activity, weekTimeText, appsContainer);
            }, 300);

        } else {
            nameView.setText(activity.getString(R.string.loading));
            btnEdit.setVisibility(View.GONE);
            btnFollow.setVisibility(View.INVISIBLE);
        }

        final Runnable fetchProfileData = new Runnable() {
            @Override
            public void run() {
                VpsApi.getUser(activity.vpsToken, finalTargetUid, new VpsApi.UserCallback() {
                    @Override
                    public void onLoaded(User user) {
                        if (!isAdded()) return;
                        if (user != null) {
                            if (user.nickname != null) {
                                nameView.setText(user.nickname);
                                if (isMe) activity.prefs.edit().putString("my_nickname", user.nickname).apply();
                            } else {
                                nameView.setText(isMe ? account.getDisplayName() : activity.getString(R.string.no_name));
                            }

                            if (user.about != null) {
                                aboutView.setText(user.about);
                                if (isMe) activity.prefs.edit().putString("my_about", user.about).apply();
                            } else {
                                aboutView.setText("");
                            }

                            if (user.photo != null && user.photo.length() > 10) {
                                File localAvatar = new File(activity.getFilesDir(), "avatar_" + myUid + ".png");
                                if (isMe && localAvatar.exists() && user.photo.startsWith("http")) {
                                } else {
                                    if (user.photo.startsWith("http")) {
                                        Glide.with(activity).load(user.photo).circleCrop().into(avatarView);
                                    } else {
                                        try {
                                            byte[] imageByteArray = android.util.Base64.decode(user.photo, android.util.Base64.DEFAULT);
                                            Glide.with(activity).asBitmap().load(imageByteArray).circleCrop().into(avatarView);
                                        } catch (Exception e) {}
                                    }
                                }
                            }

                            if (!isMe) {
                                LinearLayout appsContainer = view.findViewById(R.id.profile_apps_container);
                                // Передаем weekTimeText для обработки ситуации "Все скрыты"
                                renderOtherUserStats(user.topApps, appsContainer, activity, weekTimeText);
                            }

                        } else if (!isMe) nameView.setText(activity.getString(R.string.new_user));
                    }
                    @Override public void onError(String e) {}
                });

                // ... остальной код VpsApi.getCounts и VpsApi.checkIsFollowing остался без изменений ...
            }
        };

        if (activity.vpsToken != null) {
            fetchProfileData.run();
        } else {
            VpsApi.authenticateWithGoogle(account.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(final String token) {
                    activity.vpsToken = token;
                    fetchProfileData.run();
                }
                @Override public void onError(String error) {}
            });
        }

        return view;
    }

    // --- ОБНОВЛЕННЫЙ МЕТОД ДЛЯ ДРУГИХ ПОЛЬЗОВАТЕЛЕЙ ---
    private void renderOtherUserStats(Map<String, Long> topApps, LinearLayout container, MainActivity activity, TextView weekTimeText) {
        container.removeAllViews();
        if (topApps == null || topApps.isEmpty() || activity == null) return;

        android.content.pm.PackageManager pm = activity.getPackageManager();
        int limit = 0;
        long totalVisibleTime = 0;

        for (Map.Entry<String, Long> entry : topApps.entrySet()) {
            String pkgName = entry.getKey();

            // 1. Если приложение скрыто, пропускаем его (на его место станет следующее)
            // В реале: брать из user.hiddenApps, сейчас берем из mock
            if (mockHiddenApps.contains(pkgName)) {
                continue;
            }

            if (limit >= 10) break;

            View view = LayoutInflater.from(activity).inflate(R.layout.item_app_usage, container, false);

            if (limit >= 3) {
                view.setVisibility(View.GONE);
            }

            ImageView iconView = view.findViewById(R.id.app_icon);
            TextView nameView = view.findViewById(R.id.app_name);
            TextView timeView = view.findViewById(R.id.app_time);
            
            // Новые элементы из item_app_usage.xml
            TextView descView = view.findViewById(R.id.app_custom_description);
            ImageView lockView = view.findViewById(R.id.app_lock_icon);
            ImageView optionsBtn = view.findViewById(R.id.btn_app_options);

            // Так как мы рендерим "чужого" пользователя, меню и замочки скрываем
            if (optionsBtn != null) optionsBtn.setVisibility(View.GONE);
            if (lockView != null) lockView.setVisibility(View.GONE);

            // Обработка описания
            String description = mockDescriptions.get(pkgName); // В реале: user.appDescriptions.get(pkgName)
            if (description != null && !description.isEmpty() && descView != null) {
                descView.setText(description);
                descView.setVisibility(View.VISIBLE);
            }

            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
                nameView.setText(pm.getApplicationLabel(appInfo));
                iconView.setImageDrawable(pm.getApplicationIcon(appInfo));
            } catch (Exception e) {
                nameView.setText(pkgName);
            }

            timeView.setText(Utils.formatTime(activity, entry.getValue()));
            totalVisibleTime += entry.getValue();
            
            container.addView(view);
            limit++;
        }

        // 2. Логика если ВСЕ приложения скрыты (показываем XXXX и восклицательный знак)
        if (limit == 0 && !topApps.isEmpty()) {
            String hiddenText = activity.getString(R.string.hidden_time_placeholder) + "  "; 
            SpannableString ss = new SpannableString(hiddenText);
            
            // Вставляем иконку восклицательного знака с серым фоном в конец текста
            android.graphics.drawable.Drawable d = activity.getResources().getDrawable(R.drawable.ic_hidden_exclamation);
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            ImageSpan span = new ImageSpan(d, ImageSpan.ALIGN_BOTTOM);
            ss.setSpan(span, hiddenText.length() - 1, hiddenText.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

            weekTimeText.setText(ss);
            
            // Показываем Toast при нажатии на текст со знаком
            weekTimeText.setOnClickListener(v -> 
                Toast.makeText(activity, R.string.user_hid_time, Toast.LENGTH_SHORT).show()
            );
        } else {
            // Форматируем обычное время
            long minutes = totalVisibleTime / 1000 / 60;
            long hours = minutes / 60;
            long mins = minutes % 60;
            weekTimeText.setText(hours > 0 ? hours + " ч " + mins + " мин" : mins + " мин");
            // Убираем кликабельность, если она была установлена
            weekTimeText.setOnClickListener(null); 
        }

        View btnExpand = ((View)container.getParent()).findViewById(R.id.btn_expand_apps);
        if (btnExpand != null) {
            btnExpand.setVisibility(limit > 3 ? View.VISIBLE : View.GONE);
        }
    }

    // --- ЛОГИКА ДЛЯ ВЛАДЕЛЬЦА АККАУНТА (Для вызова из StatsHelper) ---
    // Вызывай этот метод из StatsHelper при биндинге каждого приложения!
    public void setupOwnerAppInteractions(final MainActivity activity, final View itemView, final String pkgName) {
        final ImageView optionsBtn = itemView.findViewById(R.id.btn_app_options);
        final ImageView lockIcon = itemView.findViewById(R.id.app_lock_icon);
        final TextView descView = itemView.findViewById(R.id.app_custom_description);

        if (optionsBtn == null) return;
        
        optionsBtn.setVisibility(View.VISIBLE);

        // Восстанавливаем состояние при скролле
        boolean isHidden = mockHiddenApps.contains(pkgName);
        if (lockIcon != null) lockIcon.setVisibility(isHidden ? View.VISIBLE : View.GONE);
        
        String currentDesc = mockDescriptions.get(pkgName);
        if (descView != null) {
            if (currentDesc != null && !currentDesc.isEmpty()) {
                descView.setText(currentDesc);
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setVisibility(View.GONE);
            }
        }

        // Тост при нажатии на замочек
        if (lockIcon != null) {
            lockIcon.setOnClickListener(v -> Toast.makeText(activity, R.string.app_hidden, Toast.LENGTH_SHORT).show());
        }

        // Открытие Popup Menu
        optionsBtn.setOnClickListener(v -> {
            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_app_options, null);
            final PopupWindow popupWindow = new PopupWindow(popupView, 
                    ViewGroup.LayoutParams.WRAP_CONTENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            
            // Прозрачный фон обязателен, чтобы закругления сработали
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setElevation(10f);

            TextView btnDesc = popupView.findViewById(R.id.menu_description);
            TextView btnHide = popupView.findViewById(R.id.menu_hide);

            // Логика "Скрыть"
            btnHide.setOnClickListener(v1 -> {
                popupWindow.dismiss();
                // Тоггл: скрываем/показываем
                if (mockHiddenApps.contains(pkgName)) {
                    mockHiddenApps.remove(pkgName);
                    if (lockIcon != null) lockIcon.setVisibility(View.GONE);
                } else {
                    mockHiddenApps.add(pkgName);
                    if (lockIcon != null) lockIcon.setVisibility(View.VISIBLE);
                    Toast.makeText(activity, R.string.app_hidden, Toast.LENGTH_SHORT).show();
                }
                // TODO: Отправить запрос на сервер (VpsApi.setAppHidden(pkgName, isHidden...))
            });

            // Логика "Описание"
            btnDesc.setOnClickListener(v12 -> {
                popupWindow.dismiss();
                showDescriptionDialog(activity, pkgName, descView);
            });

            // Показываем под кнопкой (с небольшим смещением)
            popupWindow.showAsDropDown(optionsBtn, -40, 0);
        });
    }

    private void showDescriptionDialog(MainActivity activity, String pkgName, TextView descView) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_description);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Легкая анимация появления (нужно прописать в стилях или оставить дефолтную Android)
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;

        ImageView btnClose = dialog.findViewById(R.id.dialog_close_btn);
        Button btnSave = dialog.findViewById(R.id.dialog_save_btn);
        EditText editDesc = dialog.findViewById(R.id.dialog_edit_description);

        // Если уже есть описание, подставляем его
        String existingDesc = mockDescriptions.get(pkgName);
        if (existingDesc != null) {
            editDesc.setText(existingDesc);
            editDesc.setSelection(existingDesc.length());
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String newDesc = editDesc.getText().toString().trim();
            mockDescriptions.put(pkgName, newDesc);
            
            if (descView != null) {
                if (!newDesc.isEmpty()) {
                    descView.setText(newDesc);
                    descView.setVisibility(View.VISIBLE);
                } else {
                    descView.setVisibility(View.GONE);
                }
            }
            dialog.dismiss();
            // TODO: Сохранить описание на сервере (VpsApi.setAppDescription(pkgName, newDesc...))
        });

        dialog.show();
    }
}
