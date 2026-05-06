package com.myonlinetime.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.tasks.Tasks;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;

public class FollowerSyncWorker extends Worker {

    public FollowerSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        
        String freshToken = null;
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken("603306715003-0ptgu4fqnldcsoon9niprvi772m2ebks.apps.googleusercontent.com")
                    .requestEmail().build();
            GoogleSignInClient client = GoogleSignIn.getClient(ctx, gso);
            
            GoogleSignInAccount account = Tasks.await(client.silentSignIn());
            freshToken = account.getIdToken();
        } catch (Exception e) {
            GoogleSignInAccount cached = GoogleSignIn.getLastSignedInAccount(ctx);
            if (cached != null) freshToken = cached.getIdToken();
        }

        if (freshToken == null) return Result.success();

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        VpsApi.authenticateWithGoogle(ctx, freshToken, new VpsApi.LoginCallback() {
            @Override public void onSuccess(String token) {
                VpsApi.getNotificationsHistory(token, new VpsApi.Callback() {
                    @Override public void onSuccess(String result) {
                        try {
                            SharedPreferences prefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
                            String oldCache = prefs.getString("notif_history_array", "[]");
                            
                            JSONArray newArray = new JSONArray(result);
                            JSONArray oldArray = new JSONArray(oldCache);
                            
                            int newCount = 0;
                            for (int i = 0; i < newArray.length(); i++) {
                                JSONObject item = newArray.getJSONObject(i);
                                if (!item.optBoolean("isRead", false) && isReallyNew(item, oldArray)) {
                                    if ("follower".equals(item.optString("type"))) {
                                        // ПЕРЕДАЕМ UID НОВОГО ПОДПИСЧИКА
                                        sendPush(ctx, item.optString("nickname"), item.optString("uid"));
                                    }
                                    newCount++;
                                }
                            }
                            
                            if (newCount > 0) {
                                prefs.edit().putString("notif_history_array", result).apply();
                                ctx.sendBroadcast(new Intent("UPDATE_BADGE_BROADCAST"));
                            }
                        } catch (Exception e) {}
                        success[0] = true;
                        latch.countDown();
                    }
                    @Override public void onError(String error) { latch.countDown(); }
                });
            }
            @Override public void onError(String error) { latch.countDown(); }
        });

        try { latch.await(); } catch (InterruptedException e) {}
        return success[0] ? Result.success() : Result.retry();
    }

    private boolean isReallyNew(JSONObject newItem, JSONArray oldArray) {
        long newTime = newItem.optLong("timestamp");
        for (int i = 0; i < oldArray.length(); i++) {
            if (oldArray.optJSONObject(i).optLong("timestamp") == newTime) return false;
        }
        return true;
    }

    private void sendPush(Context context, String nickname, String uid) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "followers_ch", 
                    context.getString(R.string.notif_channel_followers_name), 
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        
        // УКАЗЫВАЕМ ПРИЛОЖЕНИЮ ОТКРЫТЬ ПРОФИЛЬ
        intent.putExtra("open_tab", "other_profile");
        intent.putExtra("target_uid", uid);
        intent.putExtra("target_nickname", nickname);

        // Уникальный код для каждого уведомления, чтобы интенты не склеивались
        int reqCode = (int) System.currentTimeMillis();
        PendingIntent pi = PendingIntent.getActivity(context, reqCode, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        String safeNick = (nickname != null && !nickname.trim().isEmpty()) ? nickname : context.getString(R.string.notif_someone);
        String pushText = context.getString(R.string.notif_subscribed_to_you, safeNick);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "followers_ch")
                .setSmallIcon(R.drawable.ic_nav_profile)
                .setContentTitle(context.getString(R.string.notif_channel_followers_name))
                .setContentText(pushText)
                .setColor(ContextCompat.getColor(context, R.color.burgundyRed))
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(reqCode, builder.build());
    }
}
