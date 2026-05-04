package com.myonlinetime.app.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.widget.FrameLayout;
import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class EditProfileFragment extends Fragment {

    public static boolean isProfileUploading = false;
    public static long lastProfileSyncTime = 0;

    private File pendingPhotoFile = null;
    private File pendingBgFile = null;

    private ImageView localBgImageView;

    // === ИДЕНТИФИКАТОРЫ ВЫБОРА (ЗАЩИТА ОТ ГОНКИ ФАЙЛОВ ПРИ ПЛОХОМ ИНТЕРНЕТЕ) ===
    private long currentPhotoSelectionId = 0;
    private long currentBgSelectionId = 0;

    private static long penaltyEndTime = 0;
    private static final java.util.LinkedList<Long> textAttemptTimes = new java.util.LinkedList<>();
    private static final java.util.LinkedList<Long> mediaAttemptTimes = new java.util.LinkedList<>();

    private final ActivityResultLauncher<String[]> photoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { 
                if (uri != null) {
                    currentPhotoSelectionId++;
                    processMediaFile(uri, 10, true, currentPhotoSelectionId); 
                }
            }
    );

    private final ActivityResultLauncher<String[]> bgPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { 
                if (uri != null) {
                    currentBgSelectionId++;
                    processMediaFile(uri, 30, false, currentBgSelectionId); 
                }
            }
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

    private boolean isActionSpam(boolean isMedia) {
        long now = System.currentTimeMillis();
        
        if (now < penaltyEndTime) {
            penaltyEndTime = now + 30000; 
            if (getActivity() != null) Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
            return true; 
        }

        if (isMedia) {
            mediaAttemptTimes.add(now);
            while (!mediaAttemptTimes.isEmpty() && now - mediaAttemptTimes.getFirst() > 10000) mediaAttemptTimes.removeFirst();
            if (mediaAttemptTimes.size() > 5) {
                penaltyEndTime = now + 30000;
                mediaAttemptTimes.clear();
                if (getActivity() != null) Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
                return true;
            }
        } else {
            textAttemptTimes.add(now);
            while (!textAttemptTimes.isEmpty() && now - textAttemptTimes.getFirst() > 5000) textAttemptTimes.removeFirst();
            
            if (textAttemptTimes.size() > 5) {
                penaltyEndTime = now + 30000;
                textAttemptTimes.clear();
                if (getActivity() != null) Toast.makeText(getActivity(), R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return false; 
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        final View originalView = inflater.inflate(R.layout.layout_edit_profile, container, false);

        if (activity == null) return originalView;
        setupHeader(activity);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return originalView;

        // Делаем сам макет полностью прозрачным, иначе он перекроет наш фон!
        originalView.setBackgroundColor(Color.TRANSPARENT);

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null ? originalView.getLayoutParams() : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        localBgImageView = new ImageView(activity);
        localBgImageView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        localBgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        localBgImageView.setBackgroundColor(Color.parseColor("#121212")); 

        wrapper.addView(localBgImageView);
        wrapper.addView(originalView);

        loadExistingBackground(activity); 

        final String initialName = getArguments() != null ? getArguments().getString("CURRENT_NAME", "") : "";
        final String initialAbout = getArguments() != null ? getArguments().getString("CURRENT_ABOUT", "") : "";

        final EditText inputName = wrapper.findViewById(R.id.input_nickname);
        final EditText inputAbout = wrapper.findViewById(R.id.input_about);
        final TextView aboutCounter = wrapper.findViewById(R.id.text_about_counter); 
        View btnChangePhoto = wrapper.findViewById(R.id.btn_change_photo);
        View btnChangeBackground = wrapper.findViewById(R.id.btn_change_background);
        ImageView avatarPreview = wrapper.findViewById(R.id.edit_avatar_preview);
        Button btnSave = wrapper.findViewById(R.id.btn_save_changes);

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

        String customAvatarPath = activity.prefs.getString("custom_avatar_path_" + acct.getId(), null);
        if (customAvatarPath != null && new File(customAvatarPath).exists() && avatarPreview != null) {
            Glide.with(activity)
                 .load(new File(customAvatarPath))
                 .skipMemoryCache(true)
                 .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                 .circleCrop()
                 .into(avatarPreview);
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

        View.OnClickListener photoClickListener = v -> {
            if (isActionSpam(true)) return; 
            photoPicker.launch(new String[]{"image/*"});
        };
        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(photoClickListener);
        if (avatarPreview != null) avatarPreview.setOnClickListener(photoClickListener);

        if (btnChangeBackground != null) {
            btnChangeBackground.setOnClickListener(v -> {
                if (isActionSpam(true)) return; 
                bgPicker.launch(new String[]{"image/*"});
            });
        }

        btnSave.setOnClickListener(v -> { 
             final String n = inputName.getText().toString().trim();
             final String a = inputAbout.getText().toString().trim();

             // ВЫХОД БЕЗ ИЗМЕНЕНИЙ (Тихо и мирно)
             if (n.equals(initialName) && a.equals(initialAbout) && pendingPhotoFile == null && pendingBgFile == null) {
                 activity.navigator.closeSubScreen();
                 return;
             }
             
             if (n.isEmpty()) {
                 Toast.makeText(activity, R.string.err_empty_nickname, Toast.LENGTH_SHORT).show();
                 return;
             }

             if (isActionSpam(pendingPhotoFile != null || pendingBgFile != null)) return; 
             
             btnSave.setEnabled(false); 

             String uid = acct.getId();
             File finalBgFile = pendingBgFile;
             File finalPhotoFile = pendingPhotoFile;

             Runnable performOptimisticSaveAndUpload = () -> {
                 SharedPreferences.Editor editor = activity.prefs.edit();
                 editor.putString("my_nickname", n);
                 editor.putString("my_about", a);

                 final File[] safePhotoFile = {null};
                 final File[] safeBgFile = {null};

                 if (finalPhotoFile != null || finalBgFile != null) {
                     File[] files = activity.getFilesDir().listFiles();
                     if (files != null) {
                         for (File f : files) {
                             if (finalPhotoFile != null && f.getName().startsWith("avatar_" + uid)) f.delete();
                             if (finalBgFile != null && f.getName().startsWith("my_bg_" + uid)) f.delete();
                         }
                     }
                 }

                 if (finalPhotoFile != null) {
                     File pPath = new File(activity.getFilesDir(), "avatar_" + uid + "_" + System.currentTimeMillis() + ".png");
                     try { copyFile(finalPhotoFile, pPath); safePhotoFile[0] = pPath; } catch (Exception ignored) {}
                     editor.putString("custom_avatar_path_" + uid, pPath.getAbsolutePath());
                 }
                 if (finalBgFile != null) {
                     String ext = finalBgFile.getName().endsWith(".gif") ? ".gif" : ".jpg";
                     File bPath = new File(activity.getFilesDir(), "my_bg_" + uid + "_" + System.currentTimeMillis() + ext);
                     try { copyFile(finalBgFile, bPath); safeBgFile[0] = bPath; } catch (Exception ignored) {}
                     editor.putString("custom_bg_path_" + uid, bPath.getAbsolutePath());
                     editor.putBoolean("custom_bg_is_video_" + uid, false);
                 }
                 
                 editor.apply();

                 EditProfileFragment.isProfileUploading = true;
                 EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();

                 // ИЗОЛЯЦИЯ: Обновляем нижнюю аватарку ТОЛЬКО если изменили саму аватарку
                 if (finalPhotoFile != null) {
                     activity.mMemoryCache.remove("avatar_" + uid);
                     activity.updateAvatarInUI();
                 }

                 activity.navigator.closeSubScreen();
                 LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));

                 Utils.backgroundExecutor.execute(() -> {
                     File serverUploadPhoto = null;
                     File serverUploadBg = null;

                     try {
                         if (safePhotoFile[0] != null && safePhotoFile[0].exists()) {
                             serverUploadPhoto = new File(activity.getCacheDir(), "server_upload_avatar_" + uid + (safePhotoFile[0].getName().endsWith(".gif") ? ".gif" : ".png"));
                             copyFile(safePhotoFile[0], serverUploadPhoto);
                         }
                         if (safeBgFile[0] != null && safeBgFile[0].exists()) {
                             serverUploadBg = new File(activity.getCacheDir(), "server_upload_bg_" + uid + (safeBgFile[0].getName().endsWith(".gif") ? ".gif" : ".jpg"));
                             copyFile(safeBgFile[0], serverUploadBg);
                         }
                     } catch (Exception e) { e.printStackTrace(); }

                     final File isolatedPhoto = serverUploadPhoto;
                     final File isolatedBg = serverUploadBg;

                     if (activity.vpsToken != null) {
                         VpsApi.saveUserProfile(activity.vpsToken, n, a, isolatedPhoto, isolatedBg, new VpsApi.Callback() {
                             @Override 
                             public void onSuccess(String result) {
                                 try {
                                     JSONObject json = new JSONObject(result);
                                     String newPhotoUrl = json.has("photoUrl") ? json.optString("photoUrl") : json.optString("photo", null);
                                     String newBgUrl = json.has("backgroundUrl") ? json.optString("backgroundUrl") : json.optString("background", null);

                                     // ВЗЛОМ КЭША ТОЛЬКО ДЛЯ ТЕХ ФАЙЛОВ, КОТОРЫЕ МЫ ОТПРАВЛЯЛИ
                                     if (safePhotoFile[0] != null) {
                                         if (newPhotoUrl != null && !newPhotoUrl.isEmpty() && !newPhotoUrl.equals("null") && newPhotoUrl.startsWith("http")) {
                                             if (newPhotoUrl.contains("?")) newPhotoUrl = newPhotoUrl.substring(0, newPhotoUrl.indexOf("?"));
                                             newPhotoUrl += "?t=" + System.currentTimeMillis();
                                         }
                                     } else {
                                         newPhotoUrl = activity.prefs.getString("my_photo_base64", newPhotoUrl);
                                     }

                                     if (safeBgFile[0] != null) {
                                         if (newBgUrl != null && !newBgUrl.isEmpty() && !newBgUrl.equals("null") && newBgUrl.startsWith("http")) {
                                             if (newBgUrl.contains("?")) newBgUrl = newBgUrl.substring(0, newBgUrl.indexOf("?"));
                                             newBgUrl += "?t=" + System.currentTimeMillis();
                                         }
                                     } else {
                                         newBgUrl = activity.prefs.getString("my_bg_base64", newBgUrl);
                                     }

                                     final String finalPhotoUrl = newPhotoUrl;
                                     final String finalBgUrl = newBgUrl;

                                     activity.runOnUiThread(() -> {
                                         SharedPreferences.Editor successEditor = activity.prefs.edit();
                                         if (finalPhotoUrl != null && !finalPhotoUrl.isEmpty() && !finalPhotoUrl.equals("null")) {
                                             successEditor.putString("my_photo_base64", finalPhotoUrl);
                                         }
                                         if (finalBgUrl != null && !finalBgUrl.isEmpty() && !finalBgUrl.equals("null")) {
                                             successEditor.putString("my_bg_base64", finalBgUrl);
                                         }
                                         successEditor.apply();
                                         
                                         EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();
                                         EditProfileFragment.isProfileUploading = false;
                                         LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                                     });
                                 } catch (Exception ignored) {
                                     EditProfileFragment.isProfileUploading = false;
                                 } finally {
                                     if (isolatedPhoto != null && isolatedPhoto.exists()) isolatedPhoto.delete();
                                     if (isolatedBg != null && isolatedBg.exists()) isolatedBg.delete();
                                     if (pendingPhotoFile != null && pendingPhotoFile.exists()) pendingPhotoFile.delete();
                                     if (pendingBgFile != null && pendingBgFile.exists()) pendingBgFile.delete();
                                 }
                             }
                             @Override public void onError(String error) {
                                 EditProfileFragment.lastProfileSyncTime = 0; 
                                 EditProfileFragment.isProfileUploading = false;
                                 if (isolatedPhoto != null && isolatedPhoto.exists()) isolatedPhoto.delete();
                                 if (isolatedBg != null && isolatedBg.exists()) isolatedBg.delete();
                             }
                         });
                     } else {
                         EditProfileFragment.isProfileUploading = false;
                     }
                 });
             };

             if (!n.equals(initialName) && activity.vpsToken != null) {
                 VpsApi.checkNickname(activity.vpsToken, n, new VpsApi.Callback() {
                     @Override public void onSuccess(String result) {
                         activity.runOnUiThread(performOptimisticSaveAndUpload);
                     }
                     @Override public void onError(String error) {
                         activity.runOnUiThread(() -> {
                             btnSave.setEnabled(true);
                             String displayError = activity.getString(R.string.err_server);
                             try {
                                 if (error.contains("{")) {
                                     JSONObject errObj = new JSONObject(error.substring(error.indexOf("{")));
                                     if (errObj.has("error")) displayError = errObj.getString("error");
                                 } else {
                                     displayError = error;
                                 }
                             } catch (Exception ignored) {}
                             
                             Toast.makeText(activity, displayError, Toast.LENGTH_LONG).show();
                         });
                     }
                 });
             } else {
                 performOptimisticSaveAndUpload.run();
             }
        });

        return wrapper; 
    }

    private void loadExistingBackground(MainActivity activity) {
        if (localBgImageView == null) return;
        String uid = GoogleSignIn.getLastSignedInAccount(activity) != null ? GoogleSignIn.getLastSignedInAccount(activity).getId() : "";
        String myBgUrl = activity.prefs.getString("my_bg_base64", null);
        String customBgPath = activity.prefs.getString("custom_bg_path_" + uid, null);
        
        if (customBgPath != null && new File(customBgPath).exists()) {
            File bgFile = new File(customBgPath);
            Glide.with(activity).load(bgFile).signature(new ObjectKey(bgFile.lastModified())).centerCrop().into(localBgImageView);
        } else if (myBgUrl != null && !myBgUrl.isEmpty() && !myBgUrl.equals("null")) {
            Glide.with(activity).load(myBgUrl).centerCrop().into(localBgImageView);
        } else {
            localBgImageView.setImageDrawable(null);
        }
    }

    // === ПЕРЕДАЕМ selectionId ДЛЯ ЗАЩИТЫ ОТ МЕДЛЕННОГО ИНТЕРНЕТА ===
    private void processMediaFile(Uri uri, int maxMb, boolean isPhoto, long selectionId) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        Utils.backgroundExecutor.execute(() -> {
            try {
                android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null);
                long fileSize = 0;
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                    cursor.close();
                }

                long maxBytes = maxMb * 1024L * 1024L;
                if (fileSize > maxBytes) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB", Toast.LENGTH_LONG).show());
                    return; 
                }

                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) return;

                String mimeType = activity.getContentResolver().getType(uri);
                boolean isGif = mimeType != null && mimeType.contains("gif"); 
                
                String prefix = isPhoto ? "temp_avatar_" : "temp_bg_";
                String extension = isGif ? ".gif" : ".jpg"; 
                File tempFile = new File(activity.getCacheDir(), prefix + System.currentTimeMillis() + extension);
                
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192]; 
                int nRead;
                while ((nRead = is.read(buffer)) != -1) fos.write(buffer, 0, nRead);
                fos.flush(); fos.close(); is.close();

                activity.runOnUiThread(() -> {
                    // АНТИ-ГОНКА: Если пока мы обрабатывали файл, юзер выбрал другой - молча выбрасываем этот!
                    if (isPhoto && selectionId != currentPhotoSelectionId) {
                        tempFile.delete();
                        return;
                    }
                    if (!isPhoto && selectionId != currentBgSelectionId) {
                        tempFile.delete();
                        return;
                    }

                    if (isPhoto) {
                        pendingPhotoFile = tempFile;
                        ImageView avatarPreview = getView() != null ? getView().findViewById(R.id.edit_avatar_preview) : null;
                        if (avatarPreview != null) Glide.with(this).load(tempFile).circleCrop().into(avatarPreview);
                    } else {
                        pendingBgFile = tempFile;
                        if (localBgImageView != null) Glide.with(activity).load(tempFile).centerCrop().into(localBgImageView);
                    }
                });
            } catch (Exception e) {}
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
            if (!activity.navigator.hasSubScreen()) activity.headerManager.resetHeader();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) setupHeader(activity);
        }
    }
}
