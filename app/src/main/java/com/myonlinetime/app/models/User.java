package com.myonlinetime.app.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

public class User {

    // ИСПРАВЛЕНИЕ: Сервер отправляет ключ "uid", а не "id"
    @SerializedName("uid")
    public String uid;

    @SerializedName("nickname")
    public String nickname;

    @SerializedName("about")
    public String about;

    @SerializedName("photo")
    public String photo;

    // --- НОВЫЕ ПОЛЯ (ФОН И ДАТА РЕГИСТРАЦИИ) ---

    @SerializedName("background")
    public String background;

    @SerializedName("createdAt")
    public String createdAt;

    // ------------------------------------------

    @SerializedName("totalTime")
    public long totalTime;

    @SerializedName("topApps")
    public Map<String, Long> topApps;

    @SerializedName("hiddenApps")
    public List<String> hiddenApps;

    @SerializedName("appDescriptions")
    public Map<String, String> appDescriptions;

}
