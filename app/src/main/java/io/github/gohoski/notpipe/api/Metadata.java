package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.util.List;

import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;

/**
 * Created by Gleb on 09.01.2026.
 */

public interface Metadata {
    String getName(); String getHost();

    List<VideoInfo> search(String q) throws IOException;
    List<String> searchSuggestions(String q) throws IOException;
    Video getVideo(String id) throws IOException;
    List<Comment> getComments(String id) throws IOException;
    List<VideoInfo> getRelated(String id) throws IOException;
}
