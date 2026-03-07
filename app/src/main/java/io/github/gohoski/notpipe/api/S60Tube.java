package io.github.gohoski.notpipe.api;

import java.io.IOException;

import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Created by Gleb on 04.02.2026.
 * Use S60Tube for getting the video URL
 */

public class S60Tube implements VideoStream {
    public S60Tube() {}

    public String getName() { return "S60Tube"; }
    public String getHost() { return "s60tube.io.vn"; }

    @Override
    public String getVideoUrl(String id, String quality) throws IOException {
        // S60Tube starts returning the video stream only after requesting it's video page.
        // So we do just that and silly ignoring the input stream >v<
        HttpClient.execute(new HttpRequest("http://s60tube.io.vn", "/video/" + id)).close();
        return "http://s60tube.io.vn/videoplayback?v=" + id;
    }
}
