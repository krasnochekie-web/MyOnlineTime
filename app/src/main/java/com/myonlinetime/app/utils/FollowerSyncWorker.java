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
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
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
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(ctx);
        if (account == null || account.getIdToken() == null) return Result.success();

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        // 1. Авторизуемся в фоне
        VpsApi.authenticateWithGoogle(ctx, account.getIdToken(), new VpsApi.LoginCallback() {
            @Override public void onSuccess(String token) {
                // 2. Достаем историю
                VpsApi.getNotificationsHistory(token, new VpsApi.Callback() {
                    @Override public void onSuccess(String result) {
                        try {
                            SharedPreferences prefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
                            String oldCache = prefs.getString("notif_history_array", "[]");
                            
                            JSONArray newArray = new JSONArray(result);
                            JSONArray oldArray = new JSONArray(oldCache);
                            
                            // Сравниваем, появились ли новые
                            int newCount = 0;
                            for (int i = 0; i < newArray.length(); i++) {
                                JSONObject item = newArray.getJSONObject(i);
                                if (!item.optBoolean("isRead", false) && isReallyNew(item, oldArray)) {
                                    if ("follower".equals(item.optString("type"))) {
                                        sendPush(ctx, item.optString("nickname"));
                                    }
                                    newCount++;
                                }
                            }
                            
                            // Обновляем локальный кэш, чтобы бейдж загорелся
                            if (newCount > 0) {
                                prefs.edit().putString("notif_history_array", result).apply();
                                // Отправляем сигнал MainActivity обновить колокольчик
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

    private void sendPush(Context context, String nickname) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("followers_ch", "Подписчики", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_tab", "notifications"); // Откроем сразу уведомления

        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "followers_ch")
                .setSmallIcon(R.drawable.ic_nav_profile)
                .setContentTitle(context.getString(R.string.new_follower_title))
                .setContentText((nickname != null ? nickname : "Кто-то") + " подписался на вас!")
                .setColor(ContextCompat.getColor(context, R.color.burgundyRed))
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify((int)System.currentTimeMillis(), builder.build());
    }
}
