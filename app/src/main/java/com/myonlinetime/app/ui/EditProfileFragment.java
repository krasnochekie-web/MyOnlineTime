package com.myonlinetime.app.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

    private Uri pendingPhotoUri = null;
    private Uri pendingBgUri = null;

    private ImageView localPreviewBg;

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
                    handleMediaSelection(uri, 10, true, currentPhotoSelectionId); 
                }
            }
    );

    private final ActivityResultLauncher<String[]> bgPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { 
                if (uri != null) {
                    currentBgSelectionId++;
                    handleMediaSelection(uri, 30, false, currentBgSelectionId); 
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

    private void copyUriToFile(Uri uri, File dst) throws Exception {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
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
        
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(new Intent("ACTION_EDIT_PROFILE_OPENED"));

        final View originalView = inflater.inflate(R.layout.layout_edit_profile, container, false);
        originalView.setBackgroundColor(Color.TRANSPARENT);

        if (activity == null) return originalView;
        setupHeader(activity);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return originalView;

        // Внутренний слой для предпросмотра фона (чтобы не трогать глобальные элементы)
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLayoutParams(originalView.getLayoutParams() != null ? originalView.getLayoutParams() : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        originalView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        localPreviewBg = new ImageView(activity);
        localPreviewBg.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        localPreviewBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        localPreviewBg.setVisibility(View.GONE);

        wrapper.addView(localPreviewBg);
        wrapper.addView(originalView);

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
        if (customAvatarPath != null) {
            Object loadModel = customAvatarPath.startsWith("content://") ? Uri.parse(customAvatarPath) : new File(customAvatarPath);
            Glide.with(activity).load(loadModel).skipMemoryCache(true).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).circleCrop().into(avatarPreview);
        } else {
            String savedAvatar = activity.prefs.getString("my_photo_base64", null);
            if (savedAvatar != null && avatarPreview != null) {
                if (savedAvatar.startsWith("http")) Glide.with(activity).load(savedAvatar).circleCrop().into(avatarPreview);
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

             if (n.equals(initialName) && a.equals(initialAbout) && pendingPhotoUri == null && pendingBgUri == null) {
                 activity.navigator.closeSubScreen();
                 return;
             }
             
             if (n.isEmpty()) {
                 Toast.makeText(activity, R.string.err_empty_nickname, Toast.LENGTH_SHORT).show();
                 return;
             }

             if (isActionSpam(pendingPhotoUri != null || pendingBgUri != null)) return; 
             
             btnSave.setEnabled(false); 

             String uid = acct.getId();
             final Uri finalBgUri = pendingBgUri;
             final Uri finalPhotoUri = pendingPhotoUri;
             final long myGeneration = ++currentUploadGeneration;

             Runnable performOptimisticSaveAndUpload = () -> {
                 SharedPreferences.Editor editor = activity.prefs.edit();
                 editor.putString("my_nickname", n);
                 editor.putString("my_about", a);

                 if (finalPhotoUri != null) editor.putString("custom_avatar_path_" + uid, finalPhotoUri.toString());
                 if (finalBgUri != null) editor.putString("custom_bg_path_" + uid, finalBgUri.toString());
                 editor.apply();

                 EditProfileFragment.isProfileUploading = true;
                 EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();

                 activity.navigator.closeSubScreen();
                 LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));

                 Utils.backgroundExecutor.execute(() -> {
                     File localPhoto = null;
                     File localBg = null;
                     File serverUploadPhoto = null;
                     File serverUploadBg = null;

                     try {
                         if (myGeneration != currentUploadGeneration) return;

                         if (finalPhotoUri != null || finalBgUri != null) {
                             File[] files = activity.getFilesDir().listFiles();
                             if (files != null) {
                                 for (File f : files) {
                                     if (finalPhotoUri != null && f.getName().startsWith("avatar_" + uid)) f.delete();
                                     if (finalBgUri != null && f.getName().startsWith("my_bg_" + uid)) f.delete();
                                 }
                             }
                         }

                         if (finalPhotoUri != null) {
                             localPhoto = new File(activity.getFilesDir(), "avatar_" + uid + "_" + System.currentTimeMillis() + ".png");
                             copyUriToFile(finalPhotoUri, localPhoto);
                             serverUploadPhoto = new File(activity.getCacheDir(), "server_upload_avatar_" + uid + ".png");
                             copyUriToFile(finalPhotoUri, serverUploadPhoto);
                         }
                         if (finalBgUri != null) {
                             localBg = new File(activity.getFilesDir(), "my_bg_" + uid + "_" + System.currentTimeMillis() + ".jpg");
                             copyUriToFile(finalBgUri, localBg);
                             serverUploadBg = new File(activity.getCacheDir(), "server_upload_bg_" + uid + ".jpg");
                             copyUriToFile(finalBgUri, serverUploadBg);
                         }

                         final String permPhotoPath = localPhoto != null ? localPhoto.getAbsolutePath() : null;
                         final String permBgPath = localBg != null ? localBg.getAbsolutePath() : null;

                         activity.runOnUiThread(() -> {
                             if (myGeneration != currentUploadGeneration) return; 
                             SharedPreferences.Editor syncEditor = activity.prefs.edit();
                             if (permPhotoPath != null) {
                                 syncEditor.putString("custom_avatar_path_" + uid, permPhotoPath);
                                 activity.mMemoryCache.remove("avatar_" + uid);
                                 activity.updateAvatarInUI(); 
                             }
                             if (permBgPath != null) syncEditor.putString("custom_bg_path_" + uid, permBgPath);
                             syncEditor.apply();
                         });

                     } catch (Exception e) { e.printStackTrace(); }

                     final File isolatedPhoto = serverUploadPhoto;
                     final File isolatedBg = serverUploadBg;

                     if (activity.vpsToken != null) {
                         VpsApi.saveUserProfile(activity.vpsToken, n, a, isolatedPhoto, isolatedBg, new VpsApi.Callback() {
                             @Override 
                             public void onSuccess(String result) {
                                 if (myGeneration != currentUploadGeneration) {
                                     cleanupFiles(isolatedPhoto, isolatedBg);
                                     return;
                                 }

                                 try {
                                     JSONObject json = new JSONObject(result);
                                     activity.runOnUiThread(() -> {
                                         SharedPreferences.Editor successEditor = activity.prefs.edit();
                                         
                                         if (finalPhotoUri != null) {
                                             String newPhotoUrl = json.optString("photoUrl", json.optString("photo", null));
                                             if (newPhotoUrl != null && !newPhotoUrl.isEmpty() && !newPhotoUrl.equals("null") && newPhotoUrl.startsWith("http")) {
                                                 if (newPhotoUrl.contains("?")) newPhotoUrl = newPhotoUrl.substring(0, newPhotoUrl.indexOf("?"));
                                                 newPhotoUrl += "?t=" + System.currentTimeMillis();
                                                 successEditor.putString("my_photo_base64", newPhotoUrl);
                                             }
                                         }

                                         if (finalBgUri != null) {
                                             String newBgUrl = json.optString("backgroundUrl", json.optString("background", null));
                                             if (newBgUrl != null && !newBgUrl.isEmpty() && !newBgUrl.equals("null") && newBgUrl.startsWith("http")) {
                                                 if (newBgUrl.contains("?")) newBgUrl = newBgUrl.substring(0, newBgUrl.indexOf("?"));
                                                 newBgUrl += "?t=" + System.currentTimeMillis();
                                                 successEditor.putString("my_bg_base64", newBgUrl);
                                             }
                                         }

                                         successEditor.apply();
                                         EditProfileFragment.lastProfileSyncTime = System.currentTimeMillis();
                                         if (myGeneration == currentUploadGeneration) EditProfileFragment.isProfileUploading = false;
                                         LocalBroadcastManager.getInstance(activity).sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                                     });
                                 } catch (Exception ignored) {} 
                                 finally { cleanupFiles(isolatedPhoto, isolatedBg); }
                             }
                             @Override public void onError(String error) {
                                 if (myGeneration == currentUploadGeneration) EditProfileFragment.isProfileUploading = false;
                                 cleanupFiles(isolatedPhoto, isolatedBg);
                             }
                         });
                     } else {
                         if (myGeneration == currentUploadGeneration) EditProfileFragment.isProfileUploading = false;
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
                             if (myGeneration == currentUploadGeneration) EditProfileFragment.isProfileUploading = false;
                             String displayError = activity.getString(R.string.err_server);
                             try {
                                 if (error.contains("{")) {
                                     JSONObject errObj = new JSONObject(error.substring(error.indexOf("{")));
                                     if (errObj.has("error")) displayError = errObj.getString("error");
                                 } else { displayError = error; }
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

    private void cleanupFiles(File photo, File bg) {
        if (photo != null && photo.exists()) photo.delete();
        if (bg != null && bg.exists()) bg.delete();
    }

    private void handleMediaSelection(Uri uri, int maxMb, boolean isPhoto, long selectionId) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || uri == null) return;

        Utils.backgroundExecutor.execute(() -> {
            long fileSize = 0;
            try (android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                }
            } catch (Exception e) {}

            long maxBytes = maxMb * 1024L * 1024L;
            if (fileSize > maxBytes) {
                activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.err_file_size_limit) + " " + maxMb + " MB", Toast.LENGTH_LONG).show());
                return; 
            }

            activity.runOnUiThread(() -> {
                if (isPhoto && selectionId != currentPhotoSelectionId) return;
                if (!isPhoto && selectionId != currentBgSelectionId) return;

                if (isPhoto) {
                    pendingPhotoUri = uri;
                    ImageView avatarPreview = getView() != null ? getView().findViewById(R.id.edit_avatar_preview) : null;
                    if (avatarPreview != null) Glide.with(this).load(uri).circleCrop().into(avatarPreview);
                } else {
                    pendingBgUri = uri;
                    // Отрисовываем фон МГНОВЕННО на нашем прозрачном слое внутри фрагмента
                    if (localPreviewBg != null) {
                        localPreviewBg.setVisibility(View.VISIBLE);
                        Glide.with(this).load(uri).centerCrop().into(localPreviewBg);
                    }
                }
            });
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
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(new Intent("ACTION_EDIT_PROFILE_CLOSED"));
            if (!activity.navigator.hasSubScreen()) activity.headerManager.resetHeader();
        }
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
