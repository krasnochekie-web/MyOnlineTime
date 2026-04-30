package com.myonlinetime.app.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class EditProfileFragment extends Fragment {

    // Временные хранилища для новых медиафайлов
    private String pendingPhotoBase64 = null;
    private String pendingBgBase64 = null;

    // Современные лончеры для системного выбора файлов
    private final ActivityResultLauncher<String[]> photoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) processMediaFile(uri, 10, true); }
    );

    private final ActivityResultLauncher<String[]> bgPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) processMediaFile(uri, 30, false); }
    );

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
        final View view = inflater.inflate(R.layout.layout_edit_profile, container, false);

        if (activity == null) return view;
        setupHeader(activity);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return view;

        String currentName = getArguments() != null ? getArguments().getString("CURRENT_NAME", "") : "";
        String currentAbout = getArguments() != null ? getArguments().getString("CURRENT_ABOUT", "") : "";

        final EditText inputName = view.findViewById(R.id.input_nickname);
        final EditText inputAbout = view.findViewById(R.id.input_about);
        final TextView aboutCounter = view.findViewById(R.id.text_about_counter); // НОВЫЙ ЭЛЕМЕНТ В XML
        View btnChangePhoto = view.findViewById(R.id.btn_change_photo);
        View btnChangeBackground = view.findViewById(R.id.btn_change_background);
        ImageView avatarPreview = view.findViewById(R.id.edit_avatar_preview);
        View btnSave = view.findViewById(R.id.btn_save_changes);

        // Устанавливаем жесткие лимиты на уровне системы Android
        inputName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        inputAbout.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});

        inputName.setText(currentName);
        inputAbout.setText(currentAbout);

        // Логика счетчика символов для описания
        if (aboutCounter != null) {
            aboutCounter.setText(currentAbout.length() + "/1024");
            inputAbout.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    aboutCounter.setText(s.length() + "/1024");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        // Загрузка текущей аватарки
        Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + acct.getId());
        if (cachedAvatar != null && avatarPreview != null) {
            Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarPreview);
        } else {
            String savedAvatar = activity.prefs.getString("my_photo_base64", null);
            if (savedAvatar != null && avatarPreview != null) {
                if (savedAvatar.startsWith("http")) {
                    Glide.with(activity).load(savedAvatar).circleCrop().into(avatarPreview);
                } else {
                    try {
                        byte[] bytes = android.util.Base64.decode(savedAvatar, android.util.Base64.DEFAULT);
                        Glide.with(activity).load(bytes).circleCrop().into(avatarPreview);
                    } catch (Exception e){}
                }
            }
        }

        // Назначаем клики на системный пикер (выбираем картинки И видео)
        String[] mimeTypes = new String[]{"image/*", "video/*"};
        
        View.OnClickListener photoClickListener = v -> photoPicker.launch(mimeTypes);
        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(photoClickListener);
        if (avatarPreview != null) avatarPreview.setOnClickListener(photoClickListener);

        if (btnChangeBackground != null) {
            btnChangeBackground.setOnClickListener(v -> bgPicker.launch(mimeTypes));
        }

        // Сохранение изменений
        btnSave.setOnClickListener(v -> { 
             final String n = inputName.getText().toString().trim();
             final String a = inputAbout.getText().toString().trim();
             
             btnSave.setEnabled(false); // Защита от двойного клика!
             
             VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                 @Override public void onSuccess(String token) {
                     activity.vpsToken = token;
                     
                     // Вызываем обновленный метод API с фоном и аватаркой
                     VpsApi.saveUserProfile(activity.vpsToken, n, a, pendingPhotoBase64, pendingBgBase64, new VpsApi.Callback() {
                         @Override public void onSuccess(String result) {
                             if (!isAdded()) return;
                             
                             // 1. Мгновенно обновляем локальный кэш
                             activity.prefs.edit()
                                     .putString("my_nickname", n)
                                     .putString("my_about", a)
                                     .apply();
                                     
                             if (pendingPhotoBase64 != null) {
                                 activity.prefs.edit().putString("my_photo_base64", pendingPhotoBase64).apply();
                             }
                             if (pendingBgBase64 != null) {
                                 activity.prefs.edit().putString("my_bg_base64", pendingBgBase64).apply();
                                 activity.currentBgBase64 = pendingBgBase64; // Обновляем глобальную переменную
                             }

                             // 2. Рапортуем об успехе и перерисовываем фон
                             Toast.makeText(activity, R.string.saved_successfully, Toast.LENGTH_SHORT).show();
                             activity.updateGlobalBackground(true);
                             
                             // 3. Плавно возвращаемся назад
                             activity.navigator.closeSubScreen();
                         }
                         @Override public void onError(String error) { 
                             if (isAdded()) {
                                 Toast.makeText(activity, activity.getString(R.string.err_saving) + " " + error, Toast.LENGTH_LONG).show(); 
                                 btnSave.setEnabled(true);
                             }
                         }
                     });
                 }
                 @Override public void onError(String error) {
                     if (isAdded()) {
                         Toast.makeText(activity, activity.getString(R.string.err_token) + error, Toast.LENGTH_LONG).show();
                         btnSave.setEnabled(true);
                     }
                 }
             });
        });

        return view;
    }

    // ========================================================
    // ЛОГИКА ОБРАБОТКИ МЕДИА (ВЕС ФАЙЛА + BASE64)
    // ========================================================
    private void processMediaFile(Uri uri, int maxMb, boolean isPhoto) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        Utils.backgroundExecutor.execute(() -> {
            try {
                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) return;

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[16384];
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                byte[] fileBytes = buffer.toByteArray();
                is.close();

                // Проверка размера файла
                long fileSizeInMB = fileBytes.length / (1024 * 1024);
                if (fileSizeInMB >= maxMb) {
                    activity.runOnUiThread(() -> {
                        String errMsg = activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB";
                        Toast.makeText(activity, errMsg, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Превращаем в Base64
                String base64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.DEFAULT);

                activity.runOnUiThread(() -> {
                    if (isPhoto) {
                        pendingPhotoBase64 = base64;
                        ImageView avatarPreview = getView() != null ? getView().findViewById(R.id.edit_avatar_preview) : null;
                        if (avatarPreview != null) {
                            Glide.with(this).load(fileBytes).circleCrop().into(avatarPreview);
                        }
                    } else {
                        pendingBgBase64 = base64;
                        Toast.makeText(activity, R.string.background_selected_success, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.err_processing_file, Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ========================================================
    // МЕТОД ДЛЯ НАСТРОЙКИ ШАПКИ
    // ========================================================
    private void setupHeader(MainActivity activity) {
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerTitle.setText(activity.getString(R.string.edit_profile_title));
            activity.headerBackBtn.setVisibility(View.VISIBLE);
            activity.headerBackBtn.setImageResource(R.drawable.ic_math_arrow); 
            
            ImageView bellBtn = activity.findViewById(R.id.header_bell_btn);
            if (bellBtn != null) bellBtn.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !activity.navigator.hasSubScreen()) {
            activity.headerManager.resetHeader();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateGlobalBackground(true); 
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                setupHeader(activity);
                activity.updateGlobalBackground(true);
            }
        }
    }
}
