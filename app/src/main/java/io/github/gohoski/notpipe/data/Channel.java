package io.github.gohoski.notpipe.data;

import java.util.List;

/**
 * Created by Gleb on 12.03.2026.
 */

public class Channel {
    public final String title, thumbnail, banner, description;
    public final int subscriberCount, videoCount;
    public final List<VideoInfo> videos;

    public Channel(String title, String thumbnail, String banner, String description, int subscriberCount, int videoCount, List<VideoInfo> videos) {
        this.title = title;
        this.thumbnail = thumbnail;
        this.banner = banner;
        this.description = description;
        this.subscriberCount = subscriberCount;
        this.videoCount = videoCount;
        this.videos = videos;
    }

    @Override
    public String toString() {
        return "Channel{" +
                "\n  title='" + title + '\'' +
                "\n  thumbnail='" + thumbnail + '\'' +
                "\n  banner='" + banner + '\'' +
                "\n  description='" + description + '\'' +
                "\n  subscriberCount=" + subscriberCount +
                "\n  videoCount=" + videoCount +
                "\n  videos=" + videos +
                "\n}";
    }
}
