package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

public class EditProfileFragment extends Fragment {

    // КОД ЗАПРОСА ДЛЯ ФОНА
    public static final int RC_PICK_BACKGROUND = 9003;

    // Правильное создание Фрагмента с передачей параметров
    public static EditProfileFragment newInstance(String currentName, String currentAbout) {
        EditProfileFragment fragment = new EditProfileFragment();
        Bundle args = new Bundle();
        args.putString("CURRENT_NAME", currentName);
        args.putString("CURRENT_ABOUT", currentAbout);
        fragment.setArguments(args);
        return fragment;
    }

    public EditProfileFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        View view = inflater.inflate(R.layout.layout_edit_profile, container, false);

        if (activity == null) return view;

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerTitle.setText(activity.getString(R.string.edit_profile_title));
        activity.headerBackBtn.setVisibility(View.VISIBLE);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return view;

        // Достаем параметры из Bundle
        String currentName = "";
        String currentAbout = "";
        if (getArguments() != null) {
            currentName = getArguments().getString("CURRENT_NAME", "");
            currentAbout = getArguments().getString("CURRENT_ABOUT", "");
        }

        final EditText inputName = view.findViewById(R.id.input_nickname);
        final EditText inputAbout = view.findViewById(R.id.input_about);
        View btnChangePhoto = view.findViewById(R.id.btn_change_photo);
        View btnChangeBackground = view.findViewById(R.id.btn_change_background); // Новая кнопка
        ImageView avatarPreview = view.findViewById(R.id.edit_avatar_preview);

        inputName.setText(currentName);
        inputAbout.setText(currentAbout);

        Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + acct.getId());
        if (cachedAvatar != null && avatarPreview != null) {
            Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarPreview);
        }

        // Логика кнопки "Изменить фото" (Аватарка)
        View.OnClickListener photoClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                activity.startActivityForResult(intent, 9002); // 9002 - это RC_PICK_IMAGE в MainActivity
            }
        };
        
        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(photoClickListener);
        if (avatarPreview != null) avatarPreview.setOnClickListener(photoClickListener);

        // =====================================================================
        // >>> ЛОГИКА КНОПКИ "ИЗМЕНИТЬ ФОН" <<<
        // =====================================================================
        if (btnChangeBackground != null) {
            btnChangeBackground.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                // Вызываем startActivityForResult у Activity, чтобы ответ пришел в MainActivity
                activity.startActivityForResult(Intent.createChooser(intent, "Выберите фон"), RC_PICK_BACKGROUND);
            });
        }
        // =====================================================================

        // Кнопка Сохранения
        view.findViewById(R.id.btn_save_changes).setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) {
                 final String n = inputName.getText().toString();
                 final String a = inputAbout.getText().toString();
                 
                 VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                     @Override
                     public void onSuccess(String token) {
                         activity.vpsToken = token;
                         VpsApi.saveUser(activity.vpsToken, n, a, null, 0, null, new VpsApi.Callback() {
                             @Override 
                             public void onSuccess(String s) {
                                 if (!isAdded()) return; // Защита
                                 activity.prefs.edit().putString("my_nickname", n).putString("my_about", a).apply();
                                 Toast.makeText(activity, activity.getString(R.string.err_saving) + s, Toast.LENGTH_LONG).show();
                                 activity.resetHeader();
                                 activity.navigator.switchScreen(4, acct.getId()); // Возврат в профиль
                             }
                             @Override 
                             public void onError(String s) { 
                                 if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_saving) + s, Toast.LENGTH_LONG).show(); 
                             }
                         });
                     }
                     @Override
                     public void onError(String error) {
                         if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_token) + error, Toast.LENGTH_LONG).show();
                     }
                 });
            }
        });

        return view;
    }
}
