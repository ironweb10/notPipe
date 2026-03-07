package io.github.gohoski.notpipe.data;

/**
 * Created by Gleb on 09.01.2026.
 * Use -1 for int values if not available from API
 */

public class VideoInfo {
    public final String title, thumbnail, id, channel, channelThumbnail, duration;
    public final int views;

    public VideoInfo(String id, String title, String thumbnail, String channel, String channelThumbnail, String duration, int views) {
        this.title = title;
        this.thumbnail = thumbnail;
        this.id = id;
        this.channel = channel;
        this.channelThumbnail = channelThumbnail;
        this.duration = duration;
        this.views = views;
    }

    @Override
    public String toString() {
        return "VideoInfo{" +
                "title='" + title + '\'' +
                ", thumbnail='" + thumbnail + '\'' +
                ", id='" + id + '\'' +
                ", channel='" + channel + '\'' +
                ", channelThumbnail='" + channelThumbnail + '\'' +
                ", duration='" + duration + '\'' +
                ", views=" + views +
                '}';
    }
}