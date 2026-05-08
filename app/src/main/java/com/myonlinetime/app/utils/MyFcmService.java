package com.myonlinetime.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // Нужный импорт

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

public class MyFcmService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putString("fcm_token", token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        
        Map<String, String> data = remoteMessage.getData();
        if (data == null || data.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);

        // === 1. ГЛОБАЛЬНЫЙ ТУМБЛЕР ===
        if (!prefs.getBoolean("notif_general_enabled", true)) {
            return; 
        }

        String type = data.get("type");
        
        if ("follower".equals(type)) {
            // === 2. ТУМБЛЕР ПОДПИСОК ===
            if (!prefs.getBoolean("notif_followers_enabled", true)) {
                return;
            }

            String nickname = data.get("nickname");
            String targetUid = data.get("uid");
            String photo = data.get("photo");
            
            // 1. Показываем уведомление в шторке
            sendFollowerPush(nickname, targetUid);
            
            // 2. Сохраняем в историю текущего аккаунта
            saveToLocalHistory(type, targetUid, nickname, photo);
            
            // 3. МГНОВЕННО КРИЧИМ ПРИЛОЖЕНИЮ: "ОБНОВИ ИНТЕРФЕЙС!"
            // Используем LocalBroadcastManager для максимальной скорости
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("UPDATE_BADGE_BROADCAST"));
        } 
        else if ("time".equals(type) || "record".equals(type)) {
            // === 3. ТУМБЛЕР РЕКОРДОВ ===
            if (!prefs.getBoolean("notif_records_enabled", true)) {
                return;
            }
            // Здесь будет логика для рекордов, когда добавишь их на бэкенд
        }
    }

    private void saveToLocalHistory(String type, String uid, String nickname, String photo) {
        try {
            // Определяем текущую "папку" истории (по UID залогиненного юзера)
            com.google.android.gms.auth.api.signin.GoogleSignInAccount account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this);
            String currentUid = account != null ? account.getId() : "guest";
            String cacheKey = "notif_history_array_" + currentUid;

            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            String oldCache = prefs.getString(cacheKey, "[]");
            JSONArray oldArray = new JSONArray(oldCache);

            JSONObject newItem = new JSONObject();
            newItem.put("type", type);
            newItem.put("timestamp", System.currentTimeMillis());
            newItem.put("isRead", false); // Бейдж загорится, так как это новое уведомление
            newItem.put("uid", uid != null ? uid : "");
            newItem.put("nickname", nickname != null ? nickname : "");
            newItem.put("photo", photo != null ? photo : "");
            newItem.put("isFollowing", false);

            JSONArray newArray = new JSONArray();
            newArray.put(newItem); // Свежее уведомление — всегда первое
            
            for (int i = 0; i < oldArray.length(); i++) {
                newArray.put(oldArray.getJSONObject(i));
            }

            prefs.edit().putString(cacheKey, newArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendFollowerPush(String nickname, String uid) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "followers_ch", 
                    getString(R.string.notif_channel_followers_name), 
                    NotificationManager.IMPORTANCE_HIGH
            );
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_tab", "other_profile");
        intent.putExtra("target_uid", uid);
        intent.putExtra("target_nickname", nickname);

        int reqCode = (int) System.currentTimeMillis();
        PendingIntent pi = PendingIntent.getActivity(this, reqCode, intent, 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : 
                PendingIntent.FLAG_UPDATE_CURRENT);

        String safeNick = (nickname != null && !nickname.trim().isEmpty()) ? nickname : getString(R.string.notif_someone);
        String pushText = getString(R.string.notif_subscribed_to_you, safeNick);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "followers_ch")
                .setSmallIcon(R.drawable.ic_nav_profile)
                .setContentTitle(getString(R.string.notif_channel_followers_name))
                .setContentText(pushText)
                .setColor(ContextCompat.getColor(this, R.color.burgundyRed))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        nm.notify(reqCode, builder.build());
    }
                }
