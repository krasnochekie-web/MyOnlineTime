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

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

public class MyFcmService extends FirebaseMessagingService {

    // Вызывается, когда Google выдает приложению новый уникальный токен
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putString("fcm_token", token).apply();
    }

    // Вызывается в миллисекунду получения пуша от твоего Node.js сервера
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        
        Map<String, String> data = remoteMessage.getData();
        if (data == null || data.isEmpty()) return;

        String type = data.get("type");
        
        if ("follower".equals(type)) {
            String nickname = data.get("nickname");
            String targetUid = data.get("uid");
            String photo = data.get("photo"); // Достаем фото для истории
            
            // 1. Показываем всплывающее системное уведомление
            sendFollowerPush(nickname, targetUid);
            
            // 2. ВРУЧНУЮ СОХРАНЯЕМ В ИСТОРИЮ (мгновенно, без интернета!)
            saveToLocalHistory(type, targetUid, nickname, photo);
            
            // 3. Дергаем колокольчик в приложении (теперь он найдет isRead: false и загорится!)
            sendBroadcast(new Intent("UPDATE_BADGE_BROADCAST"));
        }
    }

    // === НОВАЯ МАГИЯ ДЛЯ ИСТОРИИ И БЕЙДЖА ===
    private void saveToLocalHistory(String type, String uid, String nickname, String photo) {
        try {
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            String oldCache = prefs.getString("notif_history_array", "[]");
            JSONArray oldArray = new JSONArray(oldCache);

            // Создаем объект нового уведомления
            JSONObject newItem = new JSONObject();
            newItem.put("type", type);
            newItem.put("timestamp", System.currentTimeMillis());
            newItem.put("isRead", false); // Обязательно false, чтобы зажегся бейдж!
            newItem.put("uid", uid != null ? uid : "");
            newItem.put("nickname", nickname != null ? nickname : "");
            newItem.put("photo", photo != null ? photo : "");
            newItem.put("isFollowing", false); // Обновится само при открытии истории

            // Создаем новый массив и кладем свежее уведомление на самый верх (индекс 0)
            JSONArray newArray = new JSONArray();
            newArray.put(newItem);
            
            // Перекладываем старые уведомления под новое
            for (int i = 0; i < oldArray.length(); i++) {
                newArray.put(oldArray.getJSONObject(i));
            }

            // Перезаписываем кэш
            prefs.edit().putString("notif_history_array", newArray.toString()).apply();
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
                    NotificationManager.IMPORTANCE_HIGH // HIGH для моментального всплывающего окна!
            );
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("open_tab", "other_profile");
        intent.putExtra("target_uid", uid);
        intent.putExtra("target_nickname", nickname);

        int reqCode = (int) System.currentTimeMillis();
        PendingIntent pi = PendingIntent.getActivity(this, reqCode, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

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
                                 
