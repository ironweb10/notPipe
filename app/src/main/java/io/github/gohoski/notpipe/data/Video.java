package io.github.gohoski.notpipe.data;

import java.util.Date;
import java.util.List;

/**
 * Created by Gleb on 12.01.2026.
 * Use -1 for int values if not available from API
 */

public class Video extends VideoInfo {
    public final int likes, subscribers;
    public final String description, videoUrl; // videoUrl should be for the default 360p quality
    public final Date publishedAt;
    public final List<Comment> comments;
    public final List<VideoInfo> related;

    public Video(String id, String title, String thumbnail, String channel, String channelThumbnail, String duration, int views, String description, int likes, int subscribers, Date publishedAt, String videoUrl, List<Comment> comments, List<VideoInfo> related) {
        super(id, title, thumbnail, channel, channelThumbnail, duration, views);
        this.description = description;
        this.likes = likes;
        this.subscribers = subscribers;
        this.publishedAt = publishedAt;
        this.videoUrl = videoUrl;
        this.comments = comments;
        this.related = related;
    }

    @Override
    public String toString() {
        return "Video{" +
                "likes=" + likes +
                ", subscribers=" + subscribers +
                ", description='" + description + '\'' +
                ", videoUrl='" + videoUrl + '\'' +
                ", publishedAt=" + publishedAt +
                ", " + super.toString() +
                '}';
    }
}
