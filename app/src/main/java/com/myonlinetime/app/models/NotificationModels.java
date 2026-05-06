package com.myonlinetime.app.models;

public class NotificationModels {

    public interface NotificationItem {
        int TYPE_TIME = 0;
        int TYPE_FOLLOWER = 1;
        
        int getType();
        long getTimestamp();
    }

    public static class TimeNotification implements NotificationItem {
        public String mainText;
        public String actionText;
        public long timestamp;

        public TimeNotification(String mainText, String actionText, long timestamp) {
            this.mainText = mainText;
            this.actionText = actionText;
            this.timestamp = timestamp;
        }

        @Override public int getType() { return TYPE_TIME; }
        @Override public long getTimestamp() { return timestamp; }
    }

    public static class FollowerNotification implements NotificationItem {
        public String uid;
        public String nickname;
        public String photo;
        public boolean isFollowing;
        public long timestamp;

        public FollowerNotification(long timestamp, String uid, String nickname, String photo, boolean isFollowing) {
            this.timestamp = timestamp;
            this.uid = uid;
            this.nickname = nickname;
            this.photo = photo;
            this.isFollowing = isFollowing;
        }

        @Override public int getType() { return TYPE_FOLLOWER; }
        @Override public long getTimestamp() { return timestamp; }
    }
}
