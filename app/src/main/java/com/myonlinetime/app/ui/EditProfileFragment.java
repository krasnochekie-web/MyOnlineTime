package com.myonlinetime.app.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import com.myonlinetime.app.utils.Utils;
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

// --- ИМПОРТЫ ДЛЯ АППАРАТНОГО СЖАТИЯ ВИДЕО ---
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class EditProfileFragment extends Fragment {

    private File pendingPhotoFile = null;
    private File pendingBgFile = null;
    
    private boolean isCompressing = false; 

    // === ПЕРЕМЕННЫЕ ЗАЩИТЫ ОТ СПАМА (RATE LIMITING) ===
    private static long penaltyEndTime = 0;
    private static final java.util.LinkedList<Long> textAttemptTimes = new java.util.LinkedList<>();
    private static final java.util.LinkedList<Long> mediaAttemptTimes = new java.util.LinkedList<>();

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

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    // === ЕДИНЫЙ МЕТОД ЗАЩИТЫ ===
    private boolean isActionSpam(boolean isMedia) {
        long now = System.currentTimeMillis();
        
        // 1. Если пользователь УЖЕ в бане
        if (now < penaltyEndTime) {
            // Любая попытка сбрасывает таймер наказания заново на 30 секунд
            penaltyEndTime = now + 30000; 
            if (getActivity() != null) {
                Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
            }
            return true; // Блокируем действие
        }

        // 2. Если пользователь не в бане, фиксируем попытку
        if (isMedia) {
            mediaAttemptTimes.add(now);
            // Удаляем старые клики (старше 10 секунд)
            while (!mediaAttemptTimes.isEmpty() && now - mediaAttemptTimes.getFirst() > 10000) {
                mediaAttemptTimes.removeFirst();
            }
            // Лимит для медиа: > 5 кликов за 10 секунд
            if (mediaAttemptTimes.size() > 5) {
                penaltyEndTime = now + 30000;
                if (getActivity() != null) Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
                return true;
            }
        } else {
            textAttemptTimes.add(now);
            while (!textAttemptTimes.isEmpty() && now - textAttemptTimes.getFirst() > 10000) {
                textAttemptTimes.removeFirst();
            }
            // Лимит для текста: > 10 сохранений за 10 секунд
            if (textAttemptTimes.size() > 10) {
                penaltyEndTime = now + 30000;
                if (getActivity() != null) Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        
        return false; 
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        final View view = inflater.inflate(R.layout.layout_edit_profile, container, false);

        if (activity == null) return view;
        setupHeader(activity);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return view;

        final String initialName = getArguments() != null ? getArguments().getString("CURRENT_NAME", "") : "";
        final String initialAbout = getArguments() != null ? getArguments().getString("CURRENT_ABOUT", "") : "";

        final EditText inputName = view.findViewById(R.id.input_nickname);
        final EditText inputAbout = view.findViewById(R.id.input_about);
        final TextView aboutCounter = view.findViewById(R.id.text_about_counter); 
        View btnChangePhoto = view.findViewById(R.id.btn_change_photo);
        View btnChangeBackground = view.findViewById(R.id.btn_change_background);
        ImageView avatarPreview = view.findViewById(R.id.edit_avatar_preview);
        View btnSave = view.findViewById(R.id.btn_save_changes);

        InputFilter exoticFilter = new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
                for (int i = start; i < end; i++) {
                    int type = Character.getType(source.charAt(i));
                    if (type == Character.SURROGATE || type == Character.NON_SPACING_MARK || 
                        type == Character.CONTROL || type == Character.OTHER_SYMBOL) {
                        return "";
                    }
                }
                return null;
            }
        };

        inputName.setFilters(new InputFilter[]{ exoticFilter, new InputFilter.LengthFilter(16) });
        inputAbout.setFilters(new InputFilter[]{ exoticFilter, new InputFilter.LengthFilter(1024) });

        inputName.setText(initialName);
        inputAbout.setText(initialAbout);

        if (aboutCounter != null) {
            aboutCounter.setText(initialAbout.length() + "/1024");
            inputAbout.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    aboutCounter.setText(s.length() + "/1024");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

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

        String[] photoMimeTypes = new String[]{"image/*"};
        String[] backgroundMimeTypes = new String[]{"image/*", "video/*"};
        
        View.OnClickListener photoClickListener = v -> {
            if (isActionSpam(true)) return; 
            photoPicker.launch(photoMimeTypes);
        };
        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(photoClickListener);
        if (avatarPreview != null) avatarPreview.setOnClickListener(photoClickListener);

        if (btnChangeBackground != null) {
            btnChangeBackground.setOnClickListener(v -> {
                if (isActionSpam(true)) return; 
                bgPicker.launch(backgroundMimeTypes);
            });
        }

        btnSave.setOnClickListener(v -> { 
             if (isCompressing) {
                 Toast.makeText(activity, R.string.err_wait_compressing, Toast.LENGTH_SHORT).show();
                 return;
             }
             
             final String n = inputName.getText().toString().trim();
             final String a = inputAbout.getText().toString().trim();

             // Если ничего не изменилось — выходим сразу
             if (n.equals(initialName) && a.equals(initialAbout) && pendingPhotoFile == null && pendingBgFile == null) {
                 activity.clearPreviewBackground(); 
                 activity.navigator.closeSubScreen();
                 return;
             }
             
             // Проверяем на спам (передаем true, если загружаются файлы, иначе false)
             if (isActionSpam(pendingPhotoFile != null || pendingBgFile != null)) {
                 return; 
             }
             
             btnSave.setEnabled(false); 

             // === 1. ОПТИМИСТИЧНЫЙ UI (МГНОВЕННОЕ ЛОКАЛЬНОЕ СОХРАНЕНИЕ) ===
             String uid = acct.getId();
             File finalBg = pendingBgFile;
             File finalPhoto = pendingPhotoFile;

             try {
                 if (pendingBgFile != null) {
                     boolean isVideo = pendingBgFile.getName().endsWith(".mp4");
                     File permBg = new File(activity.getFilesDir(), "my_bg_" + uid + (isVideo ? ".mp4" : ".jpg"));
                     copyFile(pendingBgFile, permBg);
                     
                     activity.prefs.edit()
                         .putString("custom_bg_path_" + uid, permBg.getAbsolutePath())
                         .putBoolean("custom_bg_is_video_" + uid, isVideo)
                         .apply();
                     finalBg = permBg;
                 }
                 if (pendingPhotoFile != null) {
                     File permAvatar = new File(activity.getFilesDir(), "avatar_" + uid + ".png");
                     copyFile(pendingPhotoFile, permAvatar);
                     activity.mMemoryCache.remove("avatar_" + uid);
                     finalPhoto = permAvatar;
                 }
             } catch (Exception e) { e.printStackTrace(); }

             activity.prefs.edit()
                 .putString("my_nickname", n)
                 .putString("my_about", a)
                 .apply();

             activity.clearPreviewBackground();
             activity.updateGlobalBackground(true);
             activity.updateAvatarInUI();
             activity.navigator.closeSubScreen();

             // === 2. ЗАГРУЗКА НА СЕРВЕР В ФОНОВОМ ПОТОКЕ ===
             File uploadBg = finalBg;
             File uploadPhoto = finalPhoto;
             
             if (activity.vpsToken != null) {
                 Utils.backgroundExecutor.execute(() -> {
                     VpsApi.saveUserProfile(activity.vpsToken, n, a, uploadPhoto, uploadBg, new VpsApi.Callback() {
                         @Override public void onSuccess(String result) {
                             if (!isAdded()) return;
                             try {
                                 JSONObject json = new JSONObject(result);
                                 String newPhotoUrl = json.optString("photoUrl", null);
                                 String newBgUrl = json.optString("backgroundUrl", null);

                                 if (newPhotoUrl != null && !newPhotoUrl.isEmpty() && !newPhotoUrl.equals("null")) {
                                     activity.prefs.edit().putString("my_photo_base64", newPhotoUrl).apply();
                                 }
                                 if (newBgUrl != null && !newBgUrl.isEmpty() && !newBgUrl.equals("null")) {
                                     activity.prefs.edit()
                                         .putString("my_bg_base64", newBgUrl)
                                         .putString("synced_bg_url_" + uid, newBgUrl) 
                                         .apply();
                                 }

                                 if (pendingPhotoFile != null && pendingPhotoFile.exists()) pendingPhotoFile.delete();
                                 if (pendingBgFile != null && pendingBgFile.exists()) pendingBgFile.delete();

                                 activity.runOnUiThread(() -> {
                                    Toast.makeText(activity, R.string.saved_successfully, Toast.LENGTH_SHORT).show();
                                 });
                             } catch (Exception e) {
                                 activity.runOnUiThread(() -> {
                                    Toast.makeText(activity, "Ошибка обработки ответа сервера", Toast.LENGTH_SHORT).show();
                                 });
                             }
                         }
                         @Override public void onError(String error) { 
                             if (isAdded()) {
                                 activity.runOnUiThread(() -> {
                                    Toast.makeText(activity, activity.getString(R.string.err_saving) + " " + error, Toast.LENGTH_LONG).show(); 
                                 });
                             }
                         }
                     });
                 });
             } else {
                 Toast.makeText(activity, "Ошибка авторизации. Перезапустите приложение.", Toast.LENGTH_LONG).show();
                 btnSave.setEnabled(true);
             }
        });

        return view;
    }

    private void processMediaFile(Uri uri, int maxMb, boolean isPhoto) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        Utils.backgroundExecutor.execute(() -> {
            try {
                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) return;

                String mimeType = activity.getContentResolver().getType(uri);
                boolean isVideoBg = !isPhoto && mimeType != null && mimeType.startsWith("video/");
                
                String prefix = isPhoto ? "temp_avatar_" : "temp_bg_";
                String extension = isPhoto ? ".jpg" : (isVideoBg ? ".mp4" : ".jpg"); 
                File tempFile = new File(activity.getCacheDir(), prefix + System.currentTimeMillis() + extension);
                
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192]; 
                int nRead;
                while ((nRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, nRead);
                }
                fos.flush();
                fos.close();
                is.close();

                long maxBytes = maxMb * 1024L * 1024L;
                
                // === АППАРАТНОЕ СЖАТИЕ ЧЕРЕЗ TRANSCODER ===
                if (isVideoBg) {
                    isCompressing = true;
                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.toast_compressing_video, Toast.LENGTH_LONG).show());
                    
                    File compressedFile = new File(activity.getCacheDir(), "compressed_bg_" + System.currentTimeMillis() + ".mp4");
                    
                    Transcoder.into(compressedFile.getAbsolutePath())
                        .addDataSource(tempFile.getAbsolutePath())
                        .setVideoTrackStrategy(DefaultVideoStrategy.atMost(540).build()) 
                        .setListener(new TranscoderListener() {
                            @Override
                            public void onTranscodeProgress(double progress) {}

                            @Override
                            public void onTranscodeCompleted(int successCode) {
                                isCompressing = false;
                                tempFile.delete(); // Удаляем исходник
                                
                                if (compressedFile.length() > maxBytes) {
                                    compressedFile.delete();
                                    activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB", Toast.LENGTH_LONG).show());
                                } else {
                                    activity.runOnUiThread(() -> {
                                        pendingBgFile = compressedFile;
                                        Toast.makeText(activity, R.string.toast_video_ready, Toast.LENGTH_SHORT).show();
                                        activity.previewBackground(compressedFile.getAbsolutePath(), true);
                                    });
                                }
                            }

                            @Override
                            public void onTranscodeCanceled() {
                                isCompressing = false;
                            }

                            @Override
                            public void onTranscodeFailed(@NonNull Throwable exception) {
                                isCompressing = false;
                                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.err_video_compress, Toast.LENGTH_SHORT).show());
                            }
                        }).transcode();
                    return; 
                }

                if (tempFile.length() > maxBytes) {
                    tempFile.delete(); 
                    activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB", Toast.LENGTH_LONG).show());
                    return;
                }

                activity.runOnUiThread(() -> {
                    if (isPhoto) {
                        pendingPhotoFile = tempFile;
                        ImageView avatarPreview = getView() != null ? getView().findViewById(R.id.edit_avatar_preview) : null;
                        if (avatarPreview != null) Glide.with(this).load(tempFile).circleCrop().into(avatarPreview);
                    } else {
                        pendingBgFile = tempFile;
                        Toast.makeText(activity, R.string.background_selected_success, Toast.LENGTH_SHORT).show();
                        activity.previewBackground(tempFile.getAbsolutePath(), false);
                    }
                });

            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.err_processing_file, Toast.LENGTH_SHORT).show());
            }
        });
    }

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
        if (activity != null) {
            activity.clearPreviewBackground();
            if (!activity.navigator.hasSubScreen()) {
                activity.headerManager.resetHeader();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity.previewBgPath == null) {
                activity.updateGlobalBackground(true); 
            }
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                setupHeader(activity);
                if (activity.previewBgPath == null) {
                    activity.updateGlobalBackground(true);
                }
            }
        }
    }
}
