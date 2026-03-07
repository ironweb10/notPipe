package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;
import io.github.gohoski.notpipe.Utils;

/**
 * Created by Gleb on 19.01.2026.
 * Implementation of Invidious API (https://docs.invidious.io/api/)
 * ---
 * yt3.ggpht.com is being replaced with yt4.ggpht.com since it has better reliability on
 * legacy devices and it works in restricted regions
 */

public class Invidious implements Metadata, VideoStream {
    private String baseUrl;

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
            videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    Utils.parseUrl(baseUrl,j.getArray("videoThumbnails").getObject(4).getString("url")), j.getString("author"),
                    j.getArray("authorThumbnails").getObject(2).getString("url")
                            .replace("https://yt3.ggpht.com", "http://yt4.ggpht.com"),
                    Utils.formatDuration(j.getInt("lengthSeconds")), j.getInt("viewCount")));
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
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            related.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    Utils.parseUrl(baseUrl,j.getArray("videoThumbnails").getObject(4).getString("url")),
                    j.getString("author"), "", Utils.formatDuration(j.getInt("lengthSeconds")), Utils.parseTextCount(j.getString("viewCountText"))));
        }
        return new Video(id, json.getString("title"),
                Utils.parseUrl(baseUrl, json.getArray("videoThumbnails").getObject(4).getString("url")), json.getString("author"),
                json.getArray("authorThumbnails").getObject(2).getString("url")
                        .replace("https://yt3.ggpht.com", "http://yt4.ggpht.com"),
                Utils.formatDuration(json.getInt("lengthSeconds")), json.getInt("viewCount"), json.getString("description"),
                json.getInt("likeCount"), Utils.parseTextCount(json.getString("subCountText")),
                new Date(json.getLong("published") * 1000L),
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
            comments.add(new Comment(j.getString("author"), j.getArray("authorThumbnails").getObject(0).getString("url")
                    .replace("https://yt3.ggpht.com", "http://yt4.ggpht.com"),
                    j.getString("content"), new Date(j.getLong("published") * 1000L)));
        }
        return comments;
    }

    // Will never get called
    @Override public List<VideoInfo> getRelated(String id) throws IOException {
        return null;
    }
}
