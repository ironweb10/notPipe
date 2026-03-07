package io.github.gohoski.notpipe.api;

import java.io.IOException;

import io.github.gohoski.notpipe.http.HttpClient;

public class Yt2009 implements VideoStream {
    private String baseUrl;

    public Yt2009(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() { return "yt2009"; }
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    @Override
    public String getVideoUrl(String id, String quality) throws IOException {
        String url;
        switch(quality) {
            case "480":
                url = "/get_480?video_id=" + id; break;
            case "720":
                url = "/exp_hd?video_id=" + id; break;
            case "1080":
                url = "/exp_hd?video_id=" + id + "&fhd=1"; break;
            default:
                url = "/channel_fh264_getvideo?v=" + id;
        }
        // We are requesting and getting the redirect URL manually to use our own User-Agent, since yt2009
        // tries to convert videos to H.264 Contrained Baseline for Android <3.0 devices based on the User-Agent,
        // which we don't actually want
        // (leads to 1. slowness 2. useless conversion since video is in an external player or converted by 2yxa
        // 3. Contrained Baseline doesn't actually fix the codec problems on most Android devices with them)
        // 4. This also automatically checks if the instance if dead
        return HttpClient.getRedirectUrl(baseUrl, url, HttpClient.VIDEO_TIMEOUT);
    }
}
