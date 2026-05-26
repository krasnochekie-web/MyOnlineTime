package com.myonlinetime.app.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import com.myonlinetime.app.utils.Utils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
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

    public static volatile boolean isProfileUploading = false;
    public static long lastProfileSyncTime = 0;

    public static volatile long currentUploadGeneration = 0;

    private File pendingPhotoFile = null;
    private File pendingBgFile = null;

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
                    // ИСПРАВЛЕНИЕ: Лимит для фона изменен с 30 до 10 МБ
                    processMediaFile(uri, 10, false, currentBgSelectionId); 
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
        
        if (activity != null) {
            // ИСПРАВЛЕНИЕ: Убрали жесткое включение фона, чтобы коллаж экранов (свечение) не ломалось
            LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_EDIT_PROFILE_OPENED"));
        }

        final View view = inflater.inflate(R.layout.layout_edit_profile, container, false);
        view.setBackgroundColor(Color.TRANSPARENT);

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
        final Button btnSave = view.findViewById(R.id.btn_save_changes);

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

        String[] photoMimeTypes = new String[]{"image/*"};
        String[] backgroundMimeTypes = new String[]{"image/*"};
        
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
            if (Boolean.TRUE.equals(btnSave.getTag())) return;

            final String n = inputName.getText().toString().trim();
            final String a = inputAbout.getText().toString().trim();

            if (n.equals(initialName) && a.equals(initialAbout) && pendingPhotoFile == null && pendingBgFile == null) {
                activity.navigator.closeSubScreen();
                return;
            }
             
            if (n.isEmpty()) {
                Toast.makeText(activity, R.string.err_empty_nickname, Toast.LENGTH_SHORT).show();
                return;
            }

            if (isActionSpam(pendingPhotoFile != null || pendingBgFile != null)) return; 
             
            btnSave.setTag(true);

            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(inputName.getWindowToken(), 0);
            }
            inputName.clearFocus();
            inputAbout.clearFocus();

            final long myGeneration = ++currentUploadGeneration;
            final String capturedToken = activity.vpsToken;
            final String currentUid = acct.getId();
            final File finalBgFile = pendingBgFile;
            final File finalPhotoFile = pendingPhotoFile;

            final long myUploadTicket = System.currentTimeMillis();
            activity.prefs.edit().putLong("active_upload_ticket", myUploadTicket).apply();

            // ИСПРАВЛЕНИЕ: Выполняем тяжелую работу (копирование файлов) в фоне, чтобы не фризить UI
            Utils.backgroundExecutor.execute(() -> {
                SharedPreferences.Editor editor = activity.prefs.edit();
                editor.putString("my_nickname", n);
                editor.putString("my_about", a);

                if (finalPhotoFile != null || finalBgFile != null) {
                    File[] files = activity.getFilesDir().listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (finalPhotoFile != null && f.getName().startsWith("avatar_" + currentUid)) f.delete();
                            if (finalBgFile != null && f.getName().startsWith("my_bg_" + currentUid)) f.delete();
                        }
                    }
                }

                if (finalPhotoFile != null) {
                    File pPath = new File(activity.getFilesDir(), "avatar_" + currentUid + "_" + System.currentTimeMillis() + ".png");
                    try { copyFile(finalPhotoFile, pPath); } catch (Exception ignored) {}
                    editor.putString("custom_avatar_path_" + currentUid, pPath.getAbsolutePath());
                }
                 
                if (finalBgFile != null) {
                    String ext = finalBgFile.getName().endsWith(".gif") ? ".gif" : ".jpg";
                    File bPath = new File(activity.getFilesDir(), "my_bg_" + currentUid + "_" + System.currentTimeMillis() + ext);
                    try { copyFile(finalBgFile, bPath); } catch (Exception ignored) {}
                    editor.putString("custom_bg_path_" + currentUid, bPath.getAbsolutePath());
                }
                 
                editor.apply();

                EditProfileFragment.isProfileUploading = true;
                EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();

                // Возвращаемся в UI-поток только для закрытия экрана и обновления картинки
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (finalPhotoFile != null) {
                        activity.mMemoryCache.remove("avatar_" + currentUid);
                        activity.updateAvatarInUI();
                    }

                    activity.clearPreviewBackground();
                    activity.updateGlobalBackground(true);
                    
                    activity.navigator.closeSubScreen();
                    LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                });

                // Продолжаем фоновую отправку на сервер
                File serverUploadPhoto = null;
                File serverUploadBg = null;

                try {
                    if (myGeneration != currentUploadGeneration) return;

                    if (finalPhotoFile != null && finalPhotoFile.exists()) {
                        serverUploadPhoto = new File(activity.getCacheDir(), "server_upload_avatar_" + currentUid + (finalPhotoFile.getName().endsWith(".gif") ? ".gif" : ".png"));
                        copyFile(finalPhotoFile, serverUploadPhoto);
                    }
                    if (finalBgFile != null && finalBgFile.exists()) {
                        serverUploadBg = new File(activity.getCacheDir(), "server_upload_bg_" + currentUid + (finalBgFile.getName().endsWith(".gif") ? ".gif" : ".jpg"));
                        copyFile(finalBgFile, serverUploadBg);
                    }
                } catch (Exception e) { e.printStackTrace(); }

                final File isolatedPhoto = serverUploadPhoto;
                final File isolatedBg = serverUploadBg;

                if (capturedToken != null && !n.equals(initialName)) {
                    VpsApi.checkNickname(capturedToken, n, new VpsApi.Callback() {
                        @Override public void onSuccess(String result) {
                            if (myGeneration != currentUploadGeneration) return;
                            executeFinalUpload(capturedToken, currentUid, n, a, isolatedPhoto, isolatedBg, myUploadTicket, myGeneration, activity);
                        }
                        @Override public void onError(String error) {
                            if (myGeneration != currentUploadGeneration) return;
                            
                            new Handler(Looper.getMainLooper()).post(() -> {
                                String displayError = activity.getString(R.string.err_server);
                                try {
                                    if (error.contains("{")) {
                                        JSONObject errObj = new JSONObject(error.substring(error.indexOf("{")));
                                        if (errObj.has("error")) displayError = errObj.getString("error");
                                    } else { displayError = error; }
                                } catch (Exception ignored) {}
                                Toast.makeText(activity, displayError, Toast.LENGTH_LONG).show();
                                
                                if (activity.prefs.getString("my_nickname", "").equals(n)) {
                                    activity.prefs.edit().putString("my_nickname", initialName).apply();
                                    LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                                }
                            });
                            executeFinalUpload(capturedToken, currentUid, initialName, a, isolatedPhoto, isolatedBg, myUploadTicket, myGeneration, activity);
                        }
                    });
                } else if (capturedToken != null) {
                    executeFinalUpload(capturedToken, currentUid, n, a, isolatedPhoto, isolatedBg, myUploadTicket, myGeneration, activity);
                }
            });
        });

        return view;
    }

    private void executeFinalUpload(String token, String targetUid, String nickname, String about, File photo, File bg, long ticket, long generation, MainActivity activity) {
        VpsApi.saveUserProfile(token, nickname, about, photo, bg, ticket, new VpsApi.Callback() {
            @Override 
            public void onSuccess(String result) {
                if (generation != currentUploadGeneration) {
                    cleanupFiles(photo, bg);
                    return;
                }

                try {
                    JSONObject json = new JSONObject(result);
                    if ("ignored".equals(json.optString("status"))) {
                        cleanupFiles(photo, bg);
                        return;
                    }
                    
                    new Handler(Looper.getMainLooper()).post(() -> {
                        SharedPreferences.Editor successEditor = activity.prefs.edit();
                        
                        if (photo != null) {
                            String newPhotoUrl = json.optString("photoUrl", json.optString("photo", null));
                            if (newPhotoUrl != null && !newPhotoUrl.isEmpty() && !newPhotoUrl.equals("null") && newPhotoUrl.startsWith("http")) {
                                if (newPhotoUrl.contains("?")) newPhotoUrl = newPhotoUrl.substring(0, newPhotoUrl.indexOf("?"));
                                successEditor.putString("my_photo_base64", newPhotoUrl);
                                successEditor.putString("synced_photo_url_" + targetUid, newPhotoUrl);
                            }
                        }

                        if (bg != null) {
                            String newBgUrl = json.optString("backgroundUrl", json.optString("background", null));
                            if (newBgUrl != null && !newBgUrl.isEmpty() && !newBgUrl.equals("null") && newBgUrl.startsWith("http")) {
                                if (newBgUrl.contains("?")) newBgUrl = newBgUrl.substring(0, newBgUrl.indexOf("?"));
                                successEditor.putString("my_bg_base64", newBgUrl);
                                successEditor.putString("synced_bg_url_" + targetUid, newBgUrl); 
                            }
                        }

                        successEditor.apply();
                        activity.prefs.edit().putLong("active_upload_ticket", System.currentTimeMillis()).apply();
                        
                        EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();
                        if (generation == currentUploadGeneration) EditProfileFragment.isProfileUploading = false;
                        LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                    });
                } catch (Exception ignored) {} 
                finally { cleanupFiles(photo, bg); }
            }
            @Override public void onError(String error) {
                if (generation == currentUploadGeneration) {
                    EditProfileFragment.isProfileUploading = false;
                    activity.prefs.edit().putLong("active_upload_ticket", System.currentTimeMillis()).apply(); 
                }
                cleanupFiles(photo, bg);
            }
        });
    }

    private void cleanupFiles(File photo, File bg) {
        if (photo != null && photo.exists()) photo.delete();
        if (bg != null && bg.exists()) bg.delete();
    }

    private void cleanupTempCacheDir(MainActivity activity) {
        Utils.backgroundExecutor.execute(() -> {
            File cacheDir = activity.getCacheDir();
            if (cacheDir != null && cacheDir.listFiles() != null) {
                for (File f : cacheDir.listFiles()) {
                    String name = f.getName();
                    if (name.startsWith("temp_bg_") || name.startsWith("temp_avatar_")) {
                        f.delete();
                    }
                }
            }
        });
    }

    private void processMediaFile(Uri uri, int maxMb, boolean isPhoto, long selectionId) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || uri == null) return;

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
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity, activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB", Toast.LENGTH_LONG).show());
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

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isPhoto && selectionId != currentPhotoSelectionId) {
                        tempFile.delete(); return;
                    }
                    if (!isPhoto && selectionId != currentBgSelectionId) {
                        tempFile.delete(); return;
                    }

                    if (isPhoto) {
                        pendingPhotoFile = tempFile;
                        ImageView avatarPreview = getView() != null ? getView().findViewById(R.id.edit_avatar_preview) : null;
                        if (avatarPreview != null) Glide.with(this).load(tempFile).circleCrop().into(avatarPreview);
                    } else {
                        pendingBgFile = tempFile;
                        activity.previewBackground(tempFile.getAbsolutePath());
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
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            cleanupTempCacheDir(activity); 
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(new Intent("ACTION_EDIT_PROFILE_CLOSED"));
            activity.clearPreviewBackground();
            if (!activity.navigator.hasSubScreen()) {
                activity.headerManager.resetHeader();
                
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (activity.navigator != null && !activity.navigator.hasSubScreen()) {
                        activity.updateGlobalBackground(false);
                    }
                }, 400);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            // ИСПРАВЛЕНИЕ: Не включаем фон жестко
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (!hidden) {
                setupHeader(activity);
            }
        }
    }
}
