package com.myonlinetime.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import org.json.JSONObject;
import java.util.LinkedList;

public class NicknameSetupController {

    private static long penaltyEndTime = 0;
    private static final LinkedList<Long> textAttemptTimes = new LinkedList<>();

    public interface OnSetupCompleteListener {
        void onComplete(String nickname); 
    }

    public static View inflateAndSetup(LayoutInflater inflater, ViewGroup container, MainActivity activity, OnSetupCompleteListener listener) {
        View setupView = inflater.inflate(R.layout.layout_nickname_setup, container, false);

        final EditText inputName = setupView.findViewById(R.id.setup_nickname_input);
        final Button btnSave = setupView.findViewById(R.id.setup_nickname_save_btn);

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

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        String defaultName = activity.prefs.getString("my_nickname", "");
        if (defaultName.isEmpty() || defaultName.equals("...") || defaultName.equals("User")) {
            if (acct != null && acct.getDisplayName() != null) {
                defaultName = acct.getDisplayName();
                defaultName = defaultName.replaceAll("[\\p{So}\\p{Cn}]", "").trim();
            }
        }
        if (defaultName.length() > 16) defaultName = defaultName.substring(0, 16);
        inputName.setText(defaultName);
        inputName.setSelection(defaultName.length());

        btnSave.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(btnSave.getTag())) return; 

            final String nickname = inputName.getText().toString().trim();

            if (nickname.isEmpty()) {
                Toast.makeText(activity, R.string.err_nickname_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            if (isActionSpam(activity)) return;

            btnSave.setTag(true);

            final long myUploadTicket = System.currentTimeMillis();
            activity.prefs.edit().putLong("active_upload_ticket", myUploadTicket).apply();

            if (activity.vpsToken != null) {
                VpsApi.checkNickname(activity.vpsToken, nickname, new VpsApi.Callback() {
                    @Override
                    public void onSuccess(String result) {
                        VpsApi.saveUserProfile(activity.vpsToken, nickname, null, null, null, myUploadTicket, new VpsApi.Callback() {
                            @Override
                            public void onSuccess(String saveResult) {
                                activity.runOnUiThread(() -> {
                                    SharedPreferences.Editor editor = activity.prefs.edit();
                                    editor.putString("my_nickname", nickname);
                                    editor.putBoolean("is_nickname_confirmed", true);
                                    editor.apply();

                                    activity.prefs.edit().putLong("active_upload_ticket", 0).apply();

                                    setupView.animate()
                                            .translationX(setupView.getWidth())
                                            .alpha(0f)
                                            .setDuration(350)
                                            .withEndAction(() -> {
                                                setupView.setVisibility(View.GONE);
                                                LocalBroadcastManager.getInstance(activity)
                                                        .sendBroadcast(new Intent("ACTION_PROFILE_UPDATED"));
                                                if (listener != null) listener.onComplete(nickname);
                                            }).start();
                                });
                            }
                            @Override
                            public void onError(String error) { handleFailure(activity, btnSave, error, myUploadTicket); }
                        });
                    }
                    @Override
                    public void onError(String error) { handleFailure(activity, btnSave, error, myUploadTicket); }
                });
            } else {
                btnSave.setTag(false);
            }
        });

        return setupView;
    }

    private static boolean isActionSpam(Context context) {
        long now = System.currentTimeMillis();
        if (now < penaltyEndTime) {
            penaltyEndTime = now + 30000; 
            Toast.makeText(context, R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
            return true; 
        }

        textAttemptTimes.add(now);
        while (!textAttemptTimes.isEmpty() && now - textAttemptTimes.getFirst() > 5000) {
            textAttemptTimes.removeFirst();
        }
        
        if (textAttemptTimes.size() > 5) {
            penaltyEndTime = now + 30000;
            textAttemptTimes.clear();
            Toast.makeText(context, R.string.err_wait_cooldown, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false; 
    }

    private static void handleFailure(MainActivity activity, Button btnSave, String error, long myUploadTicket) {
        activity.runOnUiThread(() -> {
            btnSave.setTag(false);
            
            long currentTicket = activity.prefs.getLong("active_upload_ticket", 0);
            if (currentTicket == myUploadTicket) {
                activity.prefs.edit().putLong("active_upload_ticket", 0).apply();
            }
            
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
}
