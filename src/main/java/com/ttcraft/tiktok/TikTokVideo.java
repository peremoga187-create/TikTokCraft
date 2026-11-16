package com.ttcraft.tiktok;

public final class TikTokVideo {
    public final String id, author, title, playUrl;
    public final long likes;
    public volatile String localPath, errorMsg, cookieHeader;
    public volatile boolean downloading, failed;

    public TikTokVideo(String id, String author, String title, String playUrl, long likes) {
        this.id = id; this.author = author; this.title = title; this.playUrl = playUrl; this.likes = likes;
    }
}
