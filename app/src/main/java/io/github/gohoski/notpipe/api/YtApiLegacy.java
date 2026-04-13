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
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Created by Gleb on 11.01.2026.
 * Implementation for YtAPILegacy (http://yt.swlbst.ru)
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
                    Utils.parseUrl(baseUrl, json.getString("channel_thumbnail")), "",
                    json.getString("duration"), -1, null));
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
            long views; try {
                views = Long.parseLong(j.getString("views").replaceAll("[^0-9]", ""));
            } catch(Exception ignored) { views=-1; }
            videos.add(new VideoInfo(j.getString("video_id"), j.getString("title"),
                    Utils.parseUrl(baseUrl, j.getString("thumbnail")), j.getString("author"),
                    Utils.parseUrl(baseUrl, j.getString("channel_thumbnail")), j.getString("channel_id"),
                    duration, views, Utils.parseRelativeDate(j.getString("published"))));
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
                Utils.parseUrl(baseUrl,json.getString("channel_thumbnail")), json.getString("channel_custom_url"),
                json.getString("duration"), Long.parseLong(json.getString("views")), publishedAt, json.getString("description"), likes,
                Integer.parseInt(json.getString("subscriberCount")), baseUrl + "/direct_url?video_id=" + id, comments, null);
    }

    @Override
    public String getVideoUrl(String id, String quality) throws IOException {
        return baseUrl + "/direct_url?video_id=" + id + (quality == null || quality.length() == 0 || "360".equals(quality) ? "" : "&quality=" + quality);
    }

    /** Conversion to MPEG-4 Visual or H.263 */
    public String getConvUrl(String id, int codec) throws IOException {
        return baseUrl + "/direct_url?video_id=" + id + "&codec=" + (codec == 1 ? "h263" : "mpeg4");
    }

    @Override public List<Comment> getComments(String id) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get-ytvideo-info.php").addParam("video_id", id).build();
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));

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

        return comments;
    }

    @Override public List<VideoInfo> getRelated(String id) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/get_related_videos.php").addParam("video_id", id).build();
        JSONArray arr = JSON.getArray(HttpClient.executeToString(req, 60000));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject json = arr.getObject(i);
            String duration; try {
                duration = json.getString("duration");
            } catch(JSONException ignored) { duration=null; }
            videos.add(new VideoInfo(json.getString("video_id"), json.getString("title"),
                    Utils.parseUrl(baseUrl, json.getString("thumbnail")), json.getString("author"),
                    Utils.parseUrl(baseUrl, json.getString("channel_thumbnail")), "", duration,
                    Long.parseLong(json.getString("views")), Utils.parseRelativeDate(json.getString("published_at"))));
        }
        return videos;
    }

    @Override
    public String getThumbnail(String id) {
        return baseUrl + "/thumbnail/" + id;
    }

    @Override
    public Channel getChannel(String id) throws IOException {
        HttpRequest req;
        if (id.startsWith("@"))
            req = new HttpRequest(baseUrl, "/get_author_videos.php?author=" + id);
        else
            req = new HttpRequest(baseUrl, "/get_author_videos_by_id.php?channel_id=" + id);
        JSONObject obj = JSON.getObject(HttpClient.executeToString(req));
        JSONArray arr = obj.getArray("videos");
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject json = arr.getObject(i);
            String duration; try {
                duration = json.getString("duration");
            } catch(JSONException ignored) { duration=null; }
            videos.add(new VideoInfo(json.getString("video_id"), json.getString("title"),
                    Utils.parseUrl(baseUrl, json.getString("thumbnail")), json.getString("author"),
                    Utils.parseUrl(baseUrl, json.getString("channel_thumbnail")), "",
                    duration, Integer.parseInt(json.getString("views")), Utils.parseRelativeDate(json.getString("published_at"))));
        }
        JSONObject json = obj.getObject("channel_info");
        return new Channel(json.getString("title"), Utils.parseUrl(baseUrl,json.getString("thumbnail")),
                Utils.parseUrl(baseUrl,json.getString("banner").replace("w2560", "w900")), json.getString("description"),
                Integer.parseInt(json.getString("subscriber_count")),
                Integer.parseInt(json.getString("video_count")), videos);
    }

    @Override
    public String getChannelIcon(String id) throws IOException {
//        if (id.startsWith("@")) {
//            HttpRequest req = new HttpRequest.Builder(baseUrl, "/get_author_videos.php").addParam("author", id).build();
//            return Utils.parseUrl(baseUrl, JSON.getObject(HttpClient.executeToString(req)).getObject("channel_info").getString("thumbnail"));
//        }
        return baseUrl + "/channel_icon/" + id;
    }
}
