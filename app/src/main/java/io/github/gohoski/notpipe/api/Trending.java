package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.util.List;

import io.github.gohoski.notpipe.data.VideoInfo;

/**
 * Created by Gleb on 25.01.2026.
 */

public interface Trending {
    String getName(); String getHost();

    List<VideoInfo> getTrendingVideos() throws IOException;
}
