package com.myonlinetime.app.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

public class User {

    @SerializedName("uid")
    public String uid;

    @SerializedName("nickname")
    public String nickname;

    @SerializedName("about")
    public String about;

    @SerializedName("photo")
    public String photo;

    @SerializedName("background")
    public String background;

    @SerializedName("createdAt")
    public String createdAt;

    // Поля для агрегированного профиля (заполняются после запроса)
    public int followers = 0;
    public int following = 0;
    public boolean isFollowing = false;

    @SerializedName("totalTime")
    public long totalTime;

    @SerializedName("topApps")
    public Map<String, Long> topApps;

    @SerializedName("hiddenApps")
    public List<String> hiddenApps;

    @SerializedName("appDescriptions")
    public Map<String, String> appDescriptions;

    // === P2P МАГИЯ ИМЕН: Глобальный словарь для этого пользователя ===
    @SerializedName("resolvedNames")
    public Map<String, String> resolvedNames;
    
    public boolean isNicknameConfirmed;
}
