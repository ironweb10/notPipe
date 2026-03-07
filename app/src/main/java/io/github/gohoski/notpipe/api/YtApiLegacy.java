package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.Utils;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Created by Gleb on 11.01.2026.
 * Implementation for YtAPILegacy (http://yt.legacyprojects.ru)
 */

public class YtApiLegacy implements Metadata, Trending, VideoStream {
    private String baseUrl;

    public YtApiLegacy(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() { return "YtAPILegacy"; }
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    @Override
    public List<VideoInfo> getTrendingVideos() throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/get_top_videos.php");
        JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject json = arr.getObject(i);
            videos.add(new VideoInfo(json.getString("video_id"), json.getString("title"),
                    Utils.parseUrl(baseUrl, json.getString("thumbnail")), json.getString("author"),
                    Utils.parseUrl(baseUrl, json.getString("channel_thumbnail")),
                    json.getString("duration"), -1));
        }
        return videos;
    }

    @Override public List<VideoInfo> search(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get_search_videos.php").addParam("query",q).build();
        JSONArray json = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < json.size(); i++) {
            JSONObject j = json.getObject(i);
            String duration; try {
                duration = j.getString("duration");
            } catch(JSONException ignored) { duration=""; }
            int views; try {
                views = Integer.parseInt(j.getString("views").replace(" views", "").replace(",",""));
            } catch(Exception ignored) { views=-1; }
            videos.add(new VideoInfo(j.getString("video_id"), j.getString("title"),
                    Utils.parseUrl(baseUrl, j.getString("thumbnail")), j.getString("author"),
                    Utils.parseUrl(baseUrl, j.getString("channel_thumbnail")),
                    duration, views));
        } return videos;
    }

    @Override public List<String> searchSuggestions(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get_search_suggestions.php").addParam("query",q).build();
        JSONArray json = JSON.getObject(HttpClient.executeToString(req)).getArray("suggestions");
        List<String> s = new ArrayList<String>();
        for (int i = 0; i < json.size(); i++) {
            s.add(json.getArray(i).getString(0));
        } return s;
    }

    @Override
    public Video getVideo(String id) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get-ytvideo-info.php").addParam("video_id", id).build();
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        String dateString = json.getString("published_at");
        Date publishedAt; try {
            publishedAt = new SimpleDateFormat("MMM d, yyyy", Locale.US).parse(dateString);
        } catch(ParseException ignored) {
            try {
                publishedAt = new SimpleDateFormat("dd.MM.yyyy, HH:mm:ss").parse(dateString);
            } catch(ParseException ignored2) {
                try {
                    if (dateString.length() >= 25 && dateString.charAt(22) == ':') // remove colon
                        dateString = dateString.substring(0, 22) + dateString.substring(23);
                    publishedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(dateString);
                } catch (ParseException e) {
                    e.printStackTrace();
                    publishedAt = Utils.parseRelativeDate(dateString.replace("Premiered ",""));
                }
            }
        }

        List<Comment> comments = new ArrayList<Comment>();
        JSONArray arr = json.getArray("comments");

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            Date date; try {
                date = f.parse(j.getString("published_at"));
            } catch(ParseException ignored) {
                date = Utils.parseRelativeDate(j.getString("published_at").replace(" (edited)",""));
            }
            comments.add(new Comment(j.getString("author"), Utils.parseUrl(baseUrl,j.getString("author_thumbnail")),
                    j.getString("text"), date));
        }

        int likes; try {
            likes = Integer.parseInt(json.getString("likes"));
        } catch(NumberFormatException ignored) { likes = -1; }

        return new Video(id, json.getString("title"), Utils.parseUrl(baseUrl,json.getString("thumbnail")), json.getString("author"),
                Utils.parseUrl(baseUrl,json.getString("channel_thumbnail")), json.getString("duration"), Integer.parseInt(json.getString("views")),
                json.getString("description"), likes,
                Integer.parseInt(json.getString("subscriberCount")), publishedAt, baseUrl + "/direct_url?video_id=" + id, comments, null);
    }

    @Override
    public String getVideoUrl(String id, String quality) throws IOException {
        System.out.println(baseUrl + "/direct_url?video_id=" + id + "&quality=" + quality);
        return baseUrl + "/direct_url?video_id=" + id + (quality == null || quality.length() == 0 || "360".equals(quality) ? "" : "&quality=" + quality);
    }

    /** Conversion to MPEG-4 Visual or H.263 */
    public String getConvUrl(String id, int codec) throws IOException {
        return baseUrl + "/direct_url?video_id=" + id + "&codec=" + (codec == 1 ? "h263" : "mpeg4");
    }

    // Will never get called
    @Override public List<Comment> getComments(String id) throws IOException {
        return null;
    }

    @Override public List<VideoInfo> getRelated(String id) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get_related_videos.php").addParam("video_id", id).build();
        JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject json = arr.getObject(i);
            String duration; try {
                duration = json.getString("duration");
            } catch(JSONException ignored) { duration=null; }
            videos.add(new VideoInfo(json.getString("video_id"), json.getString("title"),
                    Utils.parseUrl(baseUrl, json.getString("thumbnail")), json.getString("author"),
                    Utils.parseUrl(baseUrl, json.getString("channel_thumbnail")),
                    duration, Integer.parseInt(json.getString("views"))));
        }
        return videos;
    }
}
