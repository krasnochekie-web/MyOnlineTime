package com.myonlinetime.app.models;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public class User {
    @SerializedName("id")
    public String id;

    @SerializedName("nickname")
    public String nickname;

    @SerializedName("about")
    public String about;

    @SerializedName("photo")
    public String photo;

    @SerializedName("totalTime")
    public long totalTime;

    @SerializedName("topApps")
    public Map<String, Long> topApps;
}