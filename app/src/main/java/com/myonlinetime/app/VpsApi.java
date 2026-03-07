package com.myonlinetime.app;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.myonlinetime.app.models.User;

public class VpsApi {
    private static final String BASE_URL = "https://api.krasnocraft.ru/";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static class SaveUserPayload { String nickname, about, photo; long totalTime; Map<String, Long> topApps; }
    private static class FollowPayload { String targetUid; boolean isFollowing; }
    private static class CheckFollowPayload { String targetUid; }
    
    // НОВЫЕ ПЭЙЛОАДЫ ДЛЯ СКРЫТИЯ И ОПИСАНИЙ
    private static class AppVisibilityPayload { String pkgName; boolean isHidden; }
    private static class AppDescriptionPayload { String pkgName; String description; }

    public interface UserCallback { void onLoaded(User user); void onError(String error); }
    public interface SearchCallback { void onFound(List<User> users); }
    public interface Callback { void onSuccess(String result); void onError(String error); }
    public interface BooleanCallback { void onResult(boolean result); }
    public interface LoginCallback { void onSuccess(String ourServerToken); void onError(String error); }

    public static void authenticateWithGoogle(String googleIdToken, final LoginCallback callback) {
        String jsonBody = "{\"idToken\":\"" + googleIdToken + "\"}";
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request request = new Request.Builder()
                .url(BASE_URL + "auth/google") 
                .post(body)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
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
                        postError(callback, "Failed to parse server token");
                    }
                } else {
                    postError(callback, "Server auth failed: " + response.code());
                }
            }
        });
    }

    public static void saveUser(String ourServerToken, String nickname, String about, String photo, long totalTime, Map<String, Long> topApps, final Callback callback) {
        SaveUserPayload payload = new SaveUserPayload();
        payload.nickname = nickname;
        payload.about = about;
        payload.photo = photo;
        payload.totalTime = totalTime;
        payload.topApps = topApps;
        Request request = createAuthedRequest("save_user", ourServerToken).post(RequestBody.create(gson.toJson(payload), JSON)).build();
        enqueueCall(request, callback);
    }

    public static void getUser(String ourServerToken, String uid, final UserCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_user").newBuilder().addQueryParameter("uid", uid).build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postError(callback, e.getMessage()); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    final User user = gson.fromJson(response.body().string(), User.class);
                    if(callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { callback.onLoaded(user); }
                        });
                    }
                } else {
                    postError(callback, "Get user failed: " + response.message());
                }
            }
        });
    }

    public static void searchUsers(String ourServerToken, String query, final SearchCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "search_users").newBuilder().addQueryParameter("q", query).build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postSearchError(callback); }
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
        Request request = createAuthedRequest("set_follow", ourServerToken).post(RequestBody.create(gson.toJson(payload), JSON)).build();
        enqueueCall(request, callback);
    }

    public static void checkIsFollowing(String ourServerToken, String targetUid, final BooleanCallback callback) {
        CheckFollowPayload payload = new CheckFollowPayload();
        payload.targetUid = targetUid;
        Request request = createAuthedRequest("check_is_following", ourServerToken).post(RequestBody.create(gson.toJson(payload), JSON)).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
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
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_counts").newBuilder().addQueryParameter("uid", uid).build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        enqueueCall(request, callback);
    }

    public static void getList(String ourServerToken, String uid, String type, final SearchCallback callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "get_list").newBuilder()
                .addQueryParameter("uid", uid)
                .addQueryParameter("type", type)
                .build();
        Request request = createAuthedRequest(url, ourServerToken).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postSearchError(callback); }
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

    // --- НОВЫЙ МЕТОД: СКРЫТЬ/ПОКАЗАТЬ ПРИЛОЖЕНИЕ ---
    public static void setAppVisibility(String ourServerToken, String pkgName, boolean isHidden, final Callback callback) {
        AppVisibilityPayload payload = new AppVisibilityPayload();
        payload.pkgName = pkgName;
        payload.isHidden = isHidden;
        Request request = createAuthedRequest("set_app_visibility", ourServerToken)
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        enqueueCall(request, callback);
    }

    // --- НОВЫЙ МЕТОД: СОХРАНИТЬ ОПИСАНИЕ ПРИЛОЖЕНИЯ ---
    public static void setAppDescription(String ourServerToken, String pkgName, String description, final Callback callback) {
        AppDescriptionPayload payload = new AppDescriptionPayload();
        payload.pkgName = pkgName;
        payload.description = description;
        Request request = createAuthedRequest("set_app_description", ourServerToken)
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        enqueueCall(request, callback);
    }

    private static Request.Builder createAuthedRequest(String path, String token) {
        return new Request.Builder().url(BASE_URL + path).header("Authorization", "Bearer " + token);
    }

    private static Request.Builder createAuthedRequest(HttpUrl url, String token) {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + token);
    }

    private static void enqueueCall(Request request, final Callback callback) {
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postError(callback, e.getMessage()); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (callback == null) return;
                if (response.isSuccessful()) {
                    final String result = response.body().string();
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onSuccess(result); }
                    });
                } else {
                    postError(callback, response.code() + ": " + response.message());
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
