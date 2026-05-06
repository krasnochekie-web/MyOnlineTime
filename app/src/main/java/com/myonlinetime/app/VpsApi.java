package com.myonlinetime.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.myonlinetime.app.models.User;

public class VpsApi {
    private static final String BASE_URL = "https://api.krasnocraft.ru/";
    
    // Убрали final, чтобы иметь возможность пересоздать клиент с SSL сертификатом для Android 5.1
    private static OkHttpClient client = new OkHttpClient(); 
    
    private static final Gson gson = new Gson();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static class SaveUserPayload { 
        String nickname, about, photo, background; 
        long totalTime; 
        Map<String, Long> topApps; 
    }
    private static class FollowPayload { String targetUid; boolean isFollowing; }
    private static class CheckFollowPayload { String targetUid; }
    private static class CheckNicknamePayload { String nickname; }
    
    private static class AppVisibilityPayload { String pkgName; boolean isHidden; }
    private static class AppDescriptionPayload { String pkgName; String description; }
    
    // Payload для отправки уведомления о времени
    private static class TimeNotificationPayload { String mainText; String actionText; }

    public interface UserCallback { void onLoaded(User user); void onError(String error); }
    public interface SearchCallback { void onFound(List<User> users); }
    public interface Callback { void onSuccess(String result); void onError(String error); }
    public interface BooleanCallback { void onResult(boolean result); }
    public interface LoginCallback { void onSuccess(String ourServerToken); void onError(String error); }

    // === ФИКС ДЛЯ ANDROID 5.1 (SSL TRUST ANCHOR) ===
    public static void initSslForOldAndroid(Context context) {
        // Выполняем только на устройствах старее Android 7.0 (API 24)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) return; 

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            // Файл сертификата должен лежать в res/raw/isrgrootx1.der
            InputStream certInput = context.getResources().openRawResource(R.raw.isrgrootx1);
            Certificate ca = cf.generateCertificate(certInput);
            certInput.close();

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) tmf.getTrustManagers()[0])
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void authenticateWithGoogle(Context context, String googleIdToken, final LoginCallback callback) {
        String jsonBody = "{\"idToken\":\"" + googleIdToken + "\"}";
        RequestBody body = RequestBody.create(JSON, jsonBody);
        
        Request request = new Request.Builder()
                .url(BASE_URL + "auth/google") 
                .post(body)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                postError(callback, e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String responseBody = response.body().string();
                        Map<String, String> result = gson.fromJson(responseBody, new TypeToken<Map<String, String>>(){}.getType());
                        final String ourServerToken = result.get("accessToken");
                        if (callback != null) {
                            mainHandler.post(new Runnable() {
                                @Override public void run() { callback.onSuccess(ourServerToken); }
                            });
                        }
                    } catch (Exception e) {
                        postError(callback, context.getString(R.string.err_parse_token));
                    }
                } else {
                    String errBody = "";
                    try { errBody = response.body().string(); } catch (Exception ignored) {}
                    postError(callback, errBody.isEmpty() ? String.valueOf(response.code()) : errBody);
                }
            }
        });
    }

    public static void checkNickname(String ourServerToken, String nickname, final Callback callback) {
        CheckNicknamePayload payload = new CheckNicknamePayload();
        payload.nickname = nickname;
        Request request = createAuthedRequest("check_nickname", ourServerToken)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .build();
        enqueueCall(request, callback);
    }

    public static void saveUser(String ourServerToken, String nickname, String about, String photo, long totalTime, Map<String, Long> topApps, final Callback callback) {
        SaveUserPayload payload = new SaveUserPayload();
        payload.nickname = nickname;
        payload.about = about;
        payload.photo = photo;
        payload.totalTime = totalTime;
        payload.topApps = topApps;
        Request request = createAuthedRequest("save_user", ourServerToken).post(RequestBody.create(JSON, gson.toJson(payload))).build();
        enqueueCall(request, callback);
    }

    public static void saveUserProfile(String ourServerToken, String nickname, String about, File photoFile, File bgFile, long ticket, final Callback callback) {
        for (Call call : client.dispatcher().queuedCalls()) {
            if (call.request().url().toString().contains("save_user")) call.cancel();
        }
        for (Call call : client.dispatcher().runningCalls()) {
            if (call.request().url().toString().contains("save_user")) call.cancel();
        }

        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        if (nickname != null) builder.addFormDataPart("nickname", nickname);
        if (about != null) builder.addFormDataPart("about", about);
        
        builder.addFormDataPart("ticket", String.valueOf(ticket));

        if (photoFile != null && photoFile.exists()) {
            builder.addFormDataPart("photo", photoFile.getName(),
                    RequestBody.create(MediaType.parse("application/octet-stream"), photoFile));
        }

        if (bgFile != null && bgFile.exists()) {
            builder.addFormDataPart("background", bgFile.getName(),
                    RequestBody.create(MediaType.parse("application/octet-stream"), bgFile));
        }

        Request request = createAuthedRequest("save_user", ourServerToken)
                .post(builder.build())
                .tag("upload_profile_task")
                .build();
                
        enqueueCall(request, callback);
    }

    public static void deleteBackground(String ourServerToken, final Callback callback) {
        RequestBody body = RequestBody.create(JSON, "{}");
        Request request = createAuthedRequest("delete_background", ourServerToken)
                .post(body)
                .build();
        enqueueCall(request, callback);
    }

    public static void getUser(Context context, String ourServerToken, String uid, final UserCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_user").newBuilder()
                .addQueryParameter("uid", uid)
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { 
                if (call.isCanceled()) return;
                postError(callback, e.getMessage()); 
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    final User user = gson.fromJson(response.body().string(), User.class);
                    if(callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onLoaded(user); }
                        });
                    }
                } else {
                    postError(callback, context.getString(R.string.err_get_user) + response.message());
                }
            }
        });
    }

    public static void searchUsers(String ourServerToken, String query, final SearchCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "search_users").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { 
                if (call.isCanceled()) return;
                postSearchError(callback); 
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    TypeToken<List<User>> tokenType = new TypeToken<List<User>>() {};
                    final List<User> users = gson.fromJson(response.body().string(), tokenType.getType());
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onFound(users); }
                        });
                    }
                } else {
                    postSearchError(callback);
                }
            }
        });
    }

    public static void setFollow(String ourServerToken, String targetUid, boolean isFollowing, final Callback callback) {
        FollowPayload payload = new FollowPayload();
        payload.targetUid = targetUid;
        payload.isFollowing = isFollowing;
        Request request = createAuthedRequest("set_follow", ourServerToken).post(RequestBody.create(JSON, gson.toJson(payload))).build();
        enqueueCall(request, callback);
    }

    public static void checkIsFollowing(String ourServerToken, String targetUid, final BooleanCallback callback) {
        CheckFollowPayload payload = new CheckFollowPayload();
        payload.targetUid = targetUid;
        Request request = createAuthedRequest("check_is_following", ourServerToken).post(RequestBody.create(JSON, gson.toJson(payload))).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                if(callback!=null) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onResult(false); }
                    });
                }
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Map<String, Boolean> result = gson.fromJson(response.body().string(), new TypeToken<Map<String, Boolean>>(){}.getType());
                    final boolean isFollowing = result.containsKey("isFollowing") && result.get("isFollowing");
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(isFollowing); }
                        });
                    }
                } else {
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(false); }
                        });
                    }
                }
            }
        });
    }

    public static void getCounts(String ourServerToken, String uid, final Callback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_counts").newBuilder()
                .addQueryParameter("uid", uid)
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        enqueueCall(request, callback);
    }

    public static void getList(String ourServerToken, String uid, String type, final SearchCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_list").newBuilder()
                .addQueryParameter("uid", uid)
                .addQueryParameter("type", type)
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { 
                if (call.isCanceled()) return;
                postSearchError(callback); 
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    TypeToken<List<User>> tokenType = new TypeToken<List<User>>() {};
                    final List<User> users = gson.fromJson(response.body().string(), tokenType.getType());
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onFound(users); }
                        });
                    }
                } else {
                    postSearchError(callback);
                }
            }
        });
    }

    public static void setAppVisibility(String ourServerToken, String pkgName, boolean isHidden, final Callback callback) {
        AppVisibilityPayload payload = new AppVisibilityPayload();
        payload.pkgName = pkgName;
        payload.isHidden = isHidden;
        Request request = createAuthedRequest("set_app_visibility", ourServerToken)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .build();
        enqueueCall(request, callback);
    }

    public static void setAppDescription(String ourServerToken, String pkgName, String description, final Callback callback) {
        AppDescriptionPayload payload = new AppDescriptionPayload();
        payload.pkgName = pkgName;
        payload.description = description;
        Request request = createAuthedRequest("set_app_description", ourServerToken)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .build();
        enqueueCall(request, callback);
    }

    // ==========================================
    // НОВЫЕ МЕТОДЫ ДЛЯ УВЕДОМЛЕНИЙ (ХРАНИМ В ОБЛАКЕ)
    // ==========================================

    public static void getNotificationsHistory(String ourServerToken, final Callback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_notifications_history").newBuilder()
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        enqueueCall(request, callback);
    }

    public static void markNotificationsRead(String ourServerToken, final Callback callback) {
        RequestBody body = RequestBody.create(JSON, "{}");
        Request request = createAuthedRequest("mark_notifications_read", ourServerToken).post(body).build();
        enqueueCall(request, callback);
    }

    public static void addTimeNotification(String ourServerToken, String mainText, String actionText, final Callback callback) {
        TimeNotificationPayload payload = new TimeNotificationPayload();
        payload.mainText = mainText;
        payload.actionText = actionText;
        Request request = createAuthedRequest("add_time_notification", ourServerToken)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .build();
        enqueueCall(request, callback);
    }

    // ==========================================
    // P2P ИКОНКИ: БИНАРНАЯ ЗАГРУЗКА (БЕЗ BASE64)
    // ==========================================
    public static void uploadAppIcon(String ourServerToken, String pkgName, byte[] iconBytes) {
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("pkgName", pkgName)
                .addFormDataPart("icon", pkgName + ".png",
                        RequestBody.create(MediaType.parse("image/png"), iconBytes))
                .build();

        // ИСПРАВЛЕНИЕ: Точный маршрут сервера
        Request request = createAuthedRequest("icons/upload", ourServerToken)
                .post(body)
                .build();
                
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.body() != null) {
                    response.body().close();
                }
            }
        });
    }

    // ==========================================
    // P2P СЛОВАРЬ: ВЫГРУЗКА ИМЕН НА СЕРВЕР
    // ==========================================
    public static void syncAppNames(String ourServerToken, org.json.JSONObject names) {
        RequestBody body = RequestBody.create(JSON, names.toString());
        Request request = createAuthedRequest("sync_app_names", ourServerToken)
                .post(body)
                .build();
                
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.body() != null) {
                    response.body().close();
                }
            }
        });
    }

    // ==========================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ==========================================

    private static Request.Builder createAuthedRequest(String path, String token) {
        return new Request.Builder().url(BASE_URL + path).header("Authorization", "Bearer " + token);
    }

    private static Request.Builder createAuthedRequest(HttpUrl url, String token) {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + token);
    }

    private static void enqueueCall(Request request, final Callback callback) {
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { 
                if (call.isCanceled()) return; 
                postError(callback, e.getMessage()); 
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (callback == null) return;
                if (response.isSuccessful()) {
                    final String result = response.body().string();
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onSuccess(result); }
                    });
                } else {
                    String errBody = "";
                    try { errBody = response.body().string(); } catch (Exception ignored) {}
                    final String finalErr = errBody.isEmpty() ? String.valueOf(response.code()) : errBody;
                    postError(callback, finalErr);
                }
            }
        });
    }
    
    private static void postError(final Object callback, final String message) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback instanceof UserCallback) ((UserCallback) callback).onError(message);
                else if (callback instanceof Callback) ((Callback) callback).onError(message);
                else if (callback instanceof LoginCallback) ((LoginCallback) callback).onError(message);
            }
        });
    }
    
    private static void postSearchError(final SearchCallback callback) {
         if (callback != null) {
             mainHandler.post(new Runnable() {
                 @Override public void run() { callback.onFound(new ArrayList<User>()); }
             });
         }
    }
}
