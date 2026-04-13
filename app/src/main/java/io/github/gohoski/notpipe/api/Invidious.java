package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;
import io.github.gohoski.notpipe.Utils;

/**
 * Created by Gleb on 19.01.2026.
 * Implementation of Invidious API (https://docs.invidious.io/api/)
 */

public class Invidious implements Metadata, VideoStream {
    private String baseUrl;
    private static final int VIDEO_THUMB = 4;
    private static final int AUTHOR_THUMB = 2;

    public Invidious(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() { return "Invidious"; }
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    /**
     * It seems that Invidious has trouble loading trending videos.
     * If that issue will get fixed, this method will be uncommented.

    @Override
    public List<VideoInfo> getTrendingVideos() throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/trending");
        JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
        System.out.println(arr);
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject json = arr.getObject(i);
            videos.add(new VideoInfo(json.getString("videoId"), json.getString("title"),
                    Utils.parseUrl(baseUrl,json.getArray("videoThumbnails").getObject(4).getString("url")),
                    json.getString("author"),
                    json.getArray("authorThumbnails").getObject(2).getString("url")
                            .replace("https://yt3.ggpht.com", "http://yt4.ggpht.com"),
                    Utils.formatDuration(json.getInt("lengthSeconds")), json.getInt("viewCount")));
        }
        return videos;
    }*/

    @Override public List<VideoInfo> search(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/search").addParam("q",q).addParam("type", "video").build();
        JSONArray json = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < json.size(); i++) {
            JSONObject j = json.getObject(i);
            if ("video".equals(j.getString("type"))) // sometimes the API returns a channel for some unordinary reason…
                videos.add(new VideoInfo(
                        j.getString("videoId"), j.getString("title"),
                        Utils.parseUrl(baseUrl, j.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url")),
                        j.getString("author"),
                        Utils.parseUrl(baseUrl + "/ggpht", j.getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url")),
                        j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                        j.getLong("viewCount"), new Date(j.getLong("published") * 1000L)
                ));
        } return videos;
    }

    @Override public List<String> searchSuggestions(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/search/suggestions").addParam("q",q).build();
        JSONArray json = JSON.getObject(HttpClient.executeToString(req)).getArray("suggestions");
        List<String> s = new ArrayList<String>();
        for (int i = 0; i < json.size(); i++) {
            s.add(json.getString(i));
        } return s;
    }

    @Override public Video getVideo(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id+"?local=true");
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("recommendedVideos");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            Date published; try {
                published = sdf.parse(j.getString("published"));
            } catch(Exception e) {
                e.printStackTrace(); published = new Date();
            }
            related.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    Utils.parseUrl(baseUrl,j.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url")),
                    j.getString("author"), "", j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                    Utils.parseTextCount(j.getString("viewCountText")), published));
        }
        return new Video(id, json.getString("title"),
                Utils.parseUrl(baseUrl, json.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url")), json.getString("author"),
                Utils.parseUrl(baseUrl + "/ggpht", json.getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url")),
                json.getString("authorId"), Utils.formatDuration(json.getInt("lengthSeconds")), json.getLong("viewCount"),
                new Date(json.getLong("published") * 1000L), json.getString("description"), json.getInt("likeCount"),
                (int)Utils.parseTextCount(json.getString("subCountText")),
                Utils.parseUrl(baseUrl, json.getArray("formatStreams").getObject(0).getString("url")), null, related);
    }

    @Override public String getVideoUrl(String id, String ignored) throws IOException {
        //Invidious can provide a combined stream only in 360p, so we ignore the quality variable
        //Invidious will be disabled for non-360p via the Manager
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id+"?local=true");
        JSONObject json = JSON.getObject(HttpClient.executeToString(req, HttpClient.VIDEO_TIMEOUT));
        return Utils.parseUrl(baseUrl, json.getArray("formatStreams").getObject(0).getString("url"));
    }

    @Override public List<Comment> getComments(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/comments/"+id);
        JSONArray arr = JSON.getObject(HttpClient.executeToString(req)).getArray("comments");
        List<Comment> comments = new ArrayList<Comment>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            comments.add(new Comment(j.getString("author"), Utils.parseUrl(baseUrl + "/ggpht", j.getArray("authorThumbnails").getObject(0).getString("url")),
                    j.getString("content"), new Date(j.getLong("published") * 1000L)));
        }
        return comments;
    }

    @Override public List<VideoInfo> getRelated(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req, HttpClient.VIDEO_TIMEOUT));
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("recommendedVideos");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            Date published; try {
                published = sdf.parse(j.getString("published"));
            } catch(Exception e) {
                e.printStackTrace(); published = new Date();
            }
            related.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    Utils.parseUrl(baseUrl,j.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url")),
                    j.getString("author"), "", j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                    Utils.parseTextCount(j.getString("viewCountText")), published));
        }
        return related;
    }

    @Override
    public String getThumbnail(String id) {
        return baseUrl + "/vi/" + id + "/mqdefault.jpg";
    }

    @Override
    public Channel getChannel(String id) throws IOException {
        if (!id.startsWith("UC")) {
            id = JSON.getArray(HttpClient.executeToString(
                    new HttpRequest.Builder(baseUrl, "/api/v1/search")
                        .addParam("q", id)
                        .addParam("type", "channel").build()
                    ))
                    .getObject(0).getString("authorId");
        }
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/channels/"+id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("latestVideos");
        String thumbnail = Utils.parseUrl(baseUrl + "/ggpht", json.getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url"));
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                        Utils.parseUrl(baseUrl, j.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url")),
                        j.getString("author"), thumbnail, id,
                        Utils.formatDuration(j.getInt("lengthSeconds")), j.getLong("viewCount"), new Date(j.getLong("published") * 1000L)
                ));
            } catch(Exception e) { e.printStackTrace(); }
        }
        JSONArray banners = json.getArray("authorBanners");
        String banner = "";
        if (!banners.isEmpty())
            banner = Utils.parseUrl(baseUrl + "/ggpht", banners.getObject(0).getString("url").replace("w2560", "w900"));
        return new Channel(json.getString("author"), thumbnail, banner,
                json.getString("description"), json.getInt("subCount"), videos.size(), videos);
    }

    @Override
    public String getChannelIcon(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/channels/"+id);
        return Utils.parseUrl(baseUrl + "/ggpht", JSON.getObject(HttpClient.executeToString(req)).getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url"));
    }
}
