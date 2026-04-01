package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import java.io.File;

public class EditProfileFragment extends Fragment {

    public static final int RC_PICK_BACKGROUND = 9003;
    private VideoView backgroundVideoView;
    private String currentLoadedBgPath = null; // Трекинг для моментального обновления

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

        activity.mainHeader.setVisibility(View.VISIBLE);
        activity.headerTitle.setText(activity.getString(R.string.edit_profile_title));
        activity.headerBackBtn.setVisibility(View.VISIBLE);

        final GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct == null) return view;

        // Отложенная загрузка фона (Убирает фриз при открытии экрана)
        view.post(() -> loadBackground(activity, view));

        String currentName = getArguments() != null ? getArguments().getString("CURRENT_NAME", "") : "";
        String currentAbout = getArguments() != null ? getArguments().getString("CURRENT_ABOUT", "") : "";

        final EditText inputName = view.findViewById(R.id.input_nickname);
        final EditText inputAbout = view.findViewById(R.id.input_about);
        View btnChangePhoto = view.findViewById(R.id.btn_change_photo);
        View btnChangeBackground = view.findViewById(R.id.btn_change_background);
        ImageView avatarPreview = view.findViewById(R.id.edit_avatar_preview);

        inputName.setText(currentName);
        inputAbout.setText(currentAbout);

        Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + acct.getId());
        if (cachedAvatar != null && avatarPreview != null) {
            Glide.with(activity).load(cachedAvatar).circleCrop().into(avatarPreview);
        }

        View.OnClickListener photoClickListener = v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            activity.startActivityForResult(intent, 9002);
        };
        
        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(photoClickListener);
        if (avatarPreview != null) avatarPreview.setOnClickListener(photoClickListener);

        if (btnChangeBackground != null) {
            btnChangeBackground.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                activity.startActivityForResult(Intent.createChooser(intent, "Выберите фон"), RC_PICK_BACKGROUND);
            });
        }

        view.findViewById(R.id.btn_save_changes).setOnClickListener(v -> { 
             final String n = inputName.getText().toString();
             final String a = inputAbout.getText().toString();
             
             VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                 @Override public void onSuccess(String token) {
                     activity.vpsToken = token;
                     VpsApi.saveUser(activity.vpsToken, n, a, null, 0, null, new VpsApi.Callback() {
                         @Override public void onSuccess(String s) {
                             if (!isAdded()) return;
                             activity.prefs.edit().putString("my_nickname", n).putString("my_about", a).apply();
                             Toast.makeText(activity, activity.getString(R.string.err_saving) + s, Toast.LENGTH_LONG).show();
                             activity.resetHeader();
                             activity.navigator.switchScreen(4, acct.getId());
                         }
                         @Override public void onError(String s) { 
                             if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_saving) + s, Toast.LENGTH_LONG).show(); 
                         }
                     });
                 }
                 @Override public void onError(String error) {
                     if (isAdded()) Toast.makeText(activity, activity.getString(R.string.err_token) + error, Toast.LENGTH_LONG).show();
                 }
             });
        });

        return view;
    }

    // МЕТОД ЗАГРУЗКИ ФОНА
    private void loadBackground(MainActivity activity, View view) {
        String customBgPath = activity.prefs.getString("custom_bg_path", null);
        boolean isVideo = activity.prefs.getBoolean("custom_bg_is_video", false);
        
        if (customBgPath == null) return;
        if (customBgPath.equals(currentLoadedBgPath)) return; // Уже загружено

        currentLoadedBgPath = customBgPath;
        File bgFile = new File(customBgPath);
        
        backgroundVideoView = view.findViewById(R.id.edit_profile_custom_background_video);
        ImageView bgImageView = view.findViewById(R.id.edit_profile_custom_background_image);

        if (bgFile.exists()) {
            if (isVideo) {
                bgImageView.setVisibility(View.GONE);
                backgroundVideoView.setVisibility(View.VISIBLE);
                backgroundVideoView.setVideoPath(customBgPath);
                backgroundVideoView.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    float videoRatio = mp.getVideoWidth() / (float) mp.getVideoHeight();
                    float screenRatio = backgroundVideoView.getWidth() / (float) backgroundVideoView.getHeight();
                    float scaleX = videoRatio > screenRatio ? videoRatio / screenRatio : 1f;
                    float scaleY = videoRatio > screenRatio ? 1f : screenRatio / videoRatio;
                    backgroundVideoView.setScaleX(scaleX);
                    backgroundVideoView.setScaleY(scaleY);
                    backgroundVideoView.start();
                });
            } else {
                if (backgroundVideoView != null) backgroundVideoView.setVisibility(View.GONE);
                bgImageView.setVisibility(View.VISIBLE);
                Glide.with(activity).load(bgFile).centerCrop().into(bgImageView);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Моментально обновляем фон при возврате из галереи
        if (getView() != null && getActivity() != null) {
            loadBackground((MainActivity) getActivity(), getView());
        }
        if (backgroundVideoView != null && !backgroundVideoView.isPlaying() && backgroundVideoView.getVisibility() == View.VISIBLE) {
            backgroundVideoView.start();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (backgroundVideoView != null && backgroundVideoView.isPlaying()) {
            backgroundVideoView.pause();
        }
    }
}
