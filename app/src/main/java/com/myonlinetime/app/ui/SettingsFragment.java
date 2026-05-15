package com.myonlinetime.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import java.io.File;

public class SettingsFragment extends Fragment {

    private TextView themeAuto, themeLight, themeDark;
    private TextView regDateTxt, accountIdTxt;
    private SharedPreferences prefs;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_THEME = "selected_theme";
    private static final int RC_SIGN_IN_TRANSFER = 9003;

    private String pendingTransferIdToken = null;
    private TextView activeEmailInput = null;
    private Dialog currentTransferDialog = null;

    private final android.content.BroadcastReceiver profileUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getView() != null && isAdded()) {
                loadUserData(getView());
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_settings, container, false);
        MainActivity activity = (MainActivity) getActivity();
        
        if (activity != null) {
            prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            activity.clearPreviewBackground();
            activity.updateGlobalBackground(false);
        }

        regDateTxt = view.findViewById(R.id.settings_reg_date_txt);
        accountIdTxt = view.findViewById(R.id.settings_account_id_txt);
        
        loadUserData(view);

        // Кнопка вызова диалога смены почты
        View btnChangeEmail = view.findViewById(R.id.btn_change_email);
        if (btnChangeEmail != null) {
            btnChangeEmail.setOnClickListener(v -> {
                if (activity != null) {
                    showChangeEmailDialog(activity);
                }
            });
        }
        
        // Кнопка удаления аккаунта
        View btnDeleteAccount = view.findViewById(R.id.btn_delete_account);
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(v -> {
                if (activity != null) showDeleteAccountDialog(activity);
            });
        }
        
        View btnSwitch = view.findViewById(R.id.btn_switch_account);
        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> {
                if (activity != null && activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.updateGlobalBackground(false);
                        activity.performSignOut(); 
                        Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                        activity.startActivityForResult(signInIntent, 9001); 
                    });
                }
            });
        }
        
        View btnSignOut = view.findViewById(R.id.btn_sign_out);
        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                if (activity != null && activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                        activity.updateGlobalBackground(false);
                        activity.performSignOut();
                    });
                }
            });
        }

        View btnSignInGuest = view.findViewById(R.id.btn_sign_in_guest);
        if (btnSignInGuest != null) {
            btnSignInGuest.setOnClickListener(v -> {
                if (activity != null && activity.mGoogleSignInClient != null) {
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    activity.startActivityForResult(signInIntent, 9001); 
                }
            });
        }

        themeAuto = view.findViewById(R.id.theme_auto);
        themeLight = view.findViewById(R.id.theme_light);
        themeDark = view.findViewById(R.id.theme_dark);
        setupThemeButtons();

        view.findViewById(R.id.btn_notifications).setOnClickListener(v -> {
            if (activity != null && activity.navigator != null) {
                activity.navigator.openSubScreen(new NotificationsFragment());
            }
        });
        view.findViewById(R.id.btn_clear_cache).setOnClickListener(v -> {
            if (activity != null && activity.navigator != null) {
                activity.navigator.openSubScreen(new ClearCacheFragment());
            }
        });

        View.OnClickListener openSiteListener = v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_map)));
            startActivity(browserIntent);
        };
        view.findViewById(R.id.btn_share_app).setOnClickListener(openSiteListener);
        view.findViewById(R.id.btn_write_review).setOnClickListener(openSiteListener);

        return view;
    }

    // === ДИАЛОГ СМЕНЫ ПОЧТЫ ===
    private void showChangeEmailDialog(MainActivity activity) {
        currentTransferDialog = new Dialog(activity);
        currentTransferDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        currentTransferDialog.setContentView(R.layout.dialog_change_email);
        
        if (currentTransferDialog.getWindow() != null) {
            currentTransferDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            currentTransferDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            currentTransferDialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        }

        ImageView btnClose = currentTransferDialog.findViewById(R.id.dialog_close_btn);
        Button btnSave = currentTransferDialog.findViewById(R.id.dialog_btn_save);
        activeEmailInput = currentTransferDialog.findViewById(R.id.dialog_email_input);
        pendingTransferIdToken = null; 

        GoogleSignInAccount currentAcct = GoogleSignIn.getLastSignedInAccount(activity);
        if (currentAcct != null && currentAcct.getEmail() != null) {
            activeEmailInput.setText(currentAcct.getEmail());
        }

        btnClose.setOnClickListener(v -> currentTransferDialog.dismiss());

        activeEmailInput.setOnClickListener(v -> {
            if (activity.mGoogleSignInClient != null) {
                // Вынужденно сбрасываем сессию, чтобы Google показал окно выбора аккаунтов
                activity.mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    startActivityForResult(signInIntent, RC_SIGN_IN_TRANSFER);
                });
            }
        });

        btnSave.setOnClickListener(v -> {
            if (pendingTransferIdToken == null) {
                currentTransferDialog.dismiss(); 
                return;
            }
            
            btnSave.setEnabled(false); // Блокируем от двойного клика, но текст НЕ меняем
            
            VpsApi.transferAccount(activity.vpsToken, pendingTransferIdToken, new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String newAccessToken) {
                    activity.runOnUiThread(() -> {
                        currentTransferDialog.dismiss();
                        activity.vpsToken = newAccessToken;
                        activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                            .putString("vps_access_token", newAccessToken).apply();
                            
                        Toast.makeText(activity, R.string.toast_email_changed, Toast.LENGTH_SHORT).show();
                        if (isAdded() && getView() != null) loadUserData(getView());
                    });
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() -> {
                        btnSave.setEnabled(true);
                        
                        if (error.contains("ALREADY_REGISTERED")) {
                            Toast.makeText(activity, R.string.err_account_already_registered, Toast.LENGTH_LONG).show();
                        } else if (error.contains("SAME_ACCOUNT")) {
                            Toast.makeText(activity, R.string.err_same_account, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(activity, getString(R.string.err_server) + " " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });

        currentTransferDialog.show();
    }

    // === ПЕРЕХВАТ ВЫБОРА НОВОГО GOOGLE АККАУНТА ===
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_SIGN_IN_TRANSFER) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount newAcct = task.getResult(ApiException.class);
                if (newAcct != null) {
                    pendingTransferIdToken = newAcct.getIdToken();
                    if (activeEmailInput != null) {
                        activeEmailInput.setText(newAcct.getEmail());
                    }
                }
            } catch (ApiException e) {
                // ЮЗЕР ОТМЕНИЛ ВЫБОР!
                // Так как сессия была уничтожена для вызова окна, мы мгновенно просим выбрать старый аккаунт,
                // чтобы не оставить пользователя "выкинутым".
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null && activity.mGoogleSignInClient != null) {
                    if (currentTransferDialog != null && currentTransferDialog.isShowing()) {
                        currentTransferDialog.dismiss();
                    }
                    Toast.makeText(activity, R.string.toast_transfer_canceled_restore, Toast.LENGTH_LONG).show();
                    
                    Intent signInIntent = activity.mGoogleSignInClient.getSignInIntent();
                    // Вызываем стандартный вход MainActivity, который сам всё восстановит
                    activity.startActivityForResult(signInIntent, 9001); 
                }
            }
        }
    }

    // === ДИАЛОГ УДАЛЕНИЯ АККАУНТА ===
    private void showDeleteAccountDialog(MainActivity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_delete_account);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        }

        ImageView btnClose = dialog.findViewById(R.id.dialog_close_btn);
        Button btnDelete = dialog.findViewById(R.id.dialog_btn_delete);
        Button btnCancel = dialog.findViewById(R.id.dialog_btn_cancel);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnDelete.setOnClickListener(v -> {
            btnDelete.setEnabled(false);
            
            if (activity.vpsToken != null) {
                VpsApi.deleteAccount(activity.vpsToken, new VpsApi.Callback() {
                    @Override
                    public void onSuccess(String result) {
                        activity.runOnUiThread(() -> {
                            dialog.dismiss();
                            activity.updateGlobalBackground(false);
                            if (activity.mGoogleSignInClient != null) {
                                activity.mGoogleSignInClient.signOut();
                            }
                            activity.performSignOut();
                            Toast.makeText(activity, R.string.toast_account_deleted, Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        activity.runOnUiThread(() -> {
                            btnDelete.setEnabled(true);
                            dialog.dismiss();
                            Toast.makeText(activity, getString(R.string.err_server) + " " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } else {
                dialog.dismiss();
                activity.updateGlobalBackground(false);
                if (activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.signOut();
                }
                activity.performSignOut();
            }
        });

        dialog.show();
    }

    private void loadUserData(View view) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || view == null) return;

        View userHeaderBlock = view.findViewById(R.id.settings_user_header_block);
        View accountBlock = view.findViewById(R.id.settings_account_block);
        View guestBlock = view.findViewById(R.id.settings_guest_login_block);

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        
        if (account != null && activity.vpsToken != null) {
            if (userHeaderBlock != null) userHeaderBlock.setVisibility(View.VISIBLE);
            if (accountBlock != null) accountBlock.setVisibility(View.VISIBLE);
            if (guestBlock != null) guestBlock.setVisibility(View.GONE);

            ImageView avatarView = view.findViewById(R.id.settings_avatar);
            TextView nicknameView = view.findViewById(R.id.settings_nickname);

            if (nicknameView != null) {
                String savedName = activity.prefs.getString("my_nickname", "...");
                nicknameView.setText(savedName);
            }
            if (accountIdTxt != null) {
                accountIdTxt.setText(getString(R.string.account_id_label, account.getId()));
            }
            if (regDateTxt != null) {
                String createdAt = activity.prefs.getString("my_created_at", "");
                regDateTxt.setText(getString(R.string.settings_reg_date, createdAt.isEmpty() ? "..." : createdAt));
            }
            
            if (avatarView != null) {
                String uid = account.getId();
                
                String customAvatarPath = activity.prefs.getString("custom_avatar_path_" + uid, null);
                if (customAvatarPath != null) {
                    File localFile = new File(customAvatarPath);
                    if (localFile.exists()) {
                        Glide.with(this)
                             .load(localFile)
                             .signature(new ObjectKey(localFile.lastModified()))
                             .circleCrop()
                             .into(avatarView);
                        return; 
                    }
                }

                Bitmap cachedAvatar = activity.mMemoryCache.get("avatar_" + uid);
                if (cachedAvatar != null) {
                    Glide.with(this).load(cachedAvatar).circleCrop().into(avatarView);
                } else {
                    String savedAvatar = activity.prefs.getString("my_photo_base64", null);
                    if (savedAvatar != null) {
                        if (savedAvatar.startsWith("http")) {
                            Glide.with(this).load(savedAvatar).circleCrop().into(avatarView);
                        } else {
                            try {
                                byte[] bytes = android.util.Base64.decode(savedAvatar, android.util.Base64.DEFAULT);
                                Glide.with(this).load(bytes).circleCrop().into(avatarView);
                            } catch (Exception e){}
                        }
                    } else {
                        Glide.with(this).load(R.drawable.bg_edit_circle).circleCrop().into(avatarView);
                    }
                }
            }
        } else {
            if (userHeaderBlock != null) userHeaderBlock.setVisibility(View.GONE);
            if (accountBlock != null) accountBlock.setVisibility(View.GONE);
            if (guestBlock != null) guestBlock.setVisibility(View.VISIBLE);
        }
    }

    private void setupThemeButtons() {
        int currentTheme = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        updateThemeUI(currentTheme);
        themeAuto.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        themeLight.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_NO));
        themeDark.setOnClickListener(v -> setTheme(AppCompatDelegate.MODE_NIGHT_YES));
    }

    private void setTheme(int mode) {
        prefs.edit().putInt(KEY_THEME, mode).apply();
        updateThemeUI(mode);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void updateThemeUI(int mode) {
        themeAuto.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeLight.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        themeDark.setBackgroundResource(R.drawable.bg_theme_toggle_inactive);
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) themeAuto.setBackgroundResource(R.drawable.bg_button_active);
        else if (mode == AppCompatDelegate.MODE_NIGHT_NO) themeLight.setBackgroundResource(R.drawable.bg_button_active);
        else themeDark.setBackgroundResource(R.drawable.bg_button_active);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            activity.clearPreviewBackground();
            activity.updateGlobalBackground(false); 
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(profileUpdateReceiver, new android.content.IntentFilter("ACTION_PROFILE_UPDATED"));
        
        if (getView() != null) {
            loadUserData(getView());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(profileUpdateReceiver);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getView() != null) {
            loadUserData(getView()); 
            if (getActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) getActivity();
                activity.clearPreviewBackground();
                activity.updateGlobalBackground(false); 
            }
        }
    }
}
