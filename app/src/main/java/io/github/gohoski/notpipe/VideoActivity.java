package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.gohoski.notpipe.api.DvaUha;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.VideoStream;
import io.github.gohoski.notpipe.api.YtApiLegacy;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.ui.AdapterLinearLayout;
import io.github.gohoski.notpipe.ui.AspectRatioVideoView;
import io.github.gohoski.notpipe.ui.CommentAdapter;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ImageLoader;

public class VideoActivity extends Activity {
    String videoId;
    LinearLayout videoLayout;
    FrameLayout videoFrame;
    Context context;
    ImageView thumbnail, channelThumbnail, play;
    VideoView videoView;
    View relatedList, commentsList;
    ProgressBar relatedLoading, commentsLoading;
    ScrollView scrollView;
    ScrollView tabsScrollView;

    Video video;
    List<VideoInfo> relatedVideos = new ArrayList<VideoInfo>();
    List<Comment> comments = new ArrayList<Comment>();
    VideoAdapter relatedAdapter;
    CommentAdapter commentsAdapter;

    Metadata api;
    VideoStream videoStream;
    Config config;

    boolean relatedLoaded = false;
    boolean commentsLoaded = false;
    protected boolean isOpencore = NotPipe.SDK < 8; // OpenCORE—multimedia framework used on Android <2.2—has some bugs that need to be catched, hence this boolean

    private Hashtable<String, String> resolvedChannelIcons = new Hashtable<String, String>();
    private Hashtable<String, Boolean> fetchingChannelIcons = new Hashtable<String, Boolean>();
    private ExecutorService channelIconExecutor = Executors.newFixedThreadPool(4);

    private LoadVideoTask loadVideoTask;
    private ResolveStreamTask resolveStreamTask;
    private DownloadVideoTask downloadVideoTask;
    private ConvertVideoTask convertVideoTask;

    private static final int MAX_STREAM_RETRIES = 3;
    private int streamRetryCount = 0;
    private LoadCommentsTask loadCommentsTask;
    private LoadRelatedTask loadRelatedTask;

    private int videoPosition = 0;
    private boolean videoPlaying = false;
    private boolean videoPrepared = false;
    private String videoUrl = null;
    private boolean isUsingMetadataUrl = false;
    private boolean isTabletFullscreen = false;
    private boolean isVideoViewNeedsReload = true; // Tracks if VideoView lost its surface bounds

    private static final int VIDEO_BUFFER_TIMEOUT = 60000;
    private static final String DIR_VIDEOS = "notPipe/videos";

    private Handler systemUiHandler = new Handler();
    private Runnable hideSystemUiRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTabletFullscreen) {
                hideSystemUI();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enforce landscape orientation on tablets early to prevent networking/UI re-creation later
        if (isTablet()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        setContentView(R.layout.activity_video);

        api = Manager.getInstance().getMetadata();
        config = ConfigManager.getInstance().getConfig();
        context = this;

        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri == null) {
            videoId = intent.getStringExtra("ID");
        } else {
            videoId = uri.getHost().contains("youtube.com") ? uri.getQueryParameter("v") : uri.getLastPathSegment();
        }

        relatedAdapter = new VideoAdapter(this, R.layout.video_item, relatedVideos);
        relatedAdapter.setChannelIconListener(new VideoAdapter.ChannelIconListener() {
            @Override
            public String getResolvedIcon(String channelId) {
                return resolvedChannelIcons.get(channelId);
            }

            @Override
            public void onRequestFallbackIcon(String channelId) {
                requestChannelIconFallback(channelId);
            }
        });

        commentsAdapter = new CommentAdapter(this, R.layout.comment_item, comments);

        setupViewReferences();
        setupAdapters();
        setupTabHost();
        // Hide the tabs panel initially so we don't start loading tabs before the main video loads
        View tabHost = findViewById(android.R.id.tabhost);
        if (tabHost != null) {
            tabHost.setVisibility(View.GONE);
        }

        videoView.setVisibility(View.GONE);
        applyOpenCoreLayoutFix();
        setupClickListeners();
        setupScrollHandler();

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterFullscreenMode();
        }

        loadVideoTask = new LoadVideoTask();
        loadVideoTask.execute(videoId);
    }

    private void requestChannelIconFallback(final String channelId) {
        if (channelId == null || channelId.length() == 0 || fetchingChannelIcons.containsKey(channelId) || resolvedChannelIcons.containsKey(channelId)) {
            return;
        }
        fetchingChannelIcons.put(channelId, true);
        channelIconExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String fetchedUrl = null;
                try {
                    Metadata fallbackApi = Manager.getInstance().getMetadata();
                    if (fallbackApi != null) {
                        fetchedUrl = fallbackApi.getChannelIcon(channelId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final String resultUrl = fetchedUrl;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fetchingChannelIcons.remove(channelId);
                        if (resultUrl != null && resultUrl.length() > 0) {
                            resolvedChannelIcons.put(channelId, resultUrl);
                            if (relatedLoaded && relatedList instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) relatedList;
                                for (int i = 0; i < vg.getChildCount(); i++) {
                                    View child = vg.getChildAt(i);
                                    if (child != null) {
                                        relatedAdapter.updateChannelIconForView(child, channelId);
                                    }
                                }
                            }
                        } else {
                            resolvedChannelIcons.put(channelId, "FAILED");
                        }
                    }
                });
            }
        });
    }

    private void handleVideoClick(int position) {
        ImageLoader.clearCache();
        System.gc();
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("ID", relatedVideos.get(position).id);
        context.startActivity(intent);
    }

    private int lastScrollY = -1;
    private int lastTabsScrollY = -1;
    private int lastListHeight = -1;
    private Handler scrollHandler = new Handler();
    private Runnable scrollCheckRunnable = new Runnable() {
        @Override
        public void run() {
            boolean shouldCheck = false;
            if (scrollView != null) {
                int currentScrollY = scrollView.getScrollY();
                if (currentScrollY != lastScrollY || lastScrollY == -1) {
                    lastScrollY = currentScrollY;
                    shouldCheck = true;
                }
            }
            if (tabsScrollView != null) {
                int currentTabsScrollY = tabsScrollView.getScrollY();
                if (currentTabsScrollY != lastTabsScrollY || lastTabsScrollY == -1) {
                    lastTabsScrollY = currentTabsScrollY;
                    shouldCheck = true;
                }
            }
            int currentListHeight = 0;
            TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
            if (tabHost != null) {
                String currentTab = tabHost.getCurrentTabTag();
                View activeView = null;
                if ("related".equals(currentTab) && relatedLoaded) {
                    activeView = relatedList;
                } else if ("comments".equals(currentTab) && commentsLoaded) {
                    activeView = commentsList;
                }
                if (activeView != null) currentListHeight = activeView.getHeight();
            }
            if (currentListHeight != lastListHeight || lastListHeight == -1) {
                lastListHeight = currentListHeight;
                shouldCheck = true;
            }
            if (shouldCheck) checkVisibleItems();
            scrollHandler.postDelayed(this, 100);
        }
    };

    /**
     * Identifies exactly which views are on the screen and loads their images.
     */
    private void checkVisibleItems() {
        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost == null) return;

        String currentTab = tabHost.getCurrentTabTag();
        View activeView = null;

        if ("related".equals(currentTab) && relatedLoaded) {
            activeView = relatedList;
        } else if ("comments".equals(currentTab) && commentsLoaded) {
            activeView = commentsList;
        }

        if (activeView instanceof AdapterLinearLayout) {
            AdapterLinearLayout activeList = (AdapterLinearLayout) activeView;
            if (activeList.getChildCount() == 0) return;

            ScrollView activeScrollView = scrollView;
            ViewParent parent = activeList.getParent();
            while (parent != null) {
                if (parent instanceof ScrollView) {
                    activeScrollView = (ScrollView) parent;
                    break;
                }
                parent = parent.getParent();
            }

            if (activeScrollView == null || activeScrollView.getHeight() == 0) return;

            int[] listLoc = new int[2];
            int[] scrollLoc = new int[2];
            activeList.getLocationInWindow(listLoc);
            activeScrollView.getLocationInWindow(scrollLoc);

            int visibleTop = scrollLoc[1];
            int visibleBottom = visibleTop + activeScrollView.getHeight();

            for (int i = 0; i < activeList.getChildCount(); i++) {
                View child = activeList.getChildAt(i);
                // Prevent zero-height unlaid-out children from prematurely evaluating as visible
                if (child == null || child.getHeight() == 0) continue;

                int childTop = listLoc[1] + child.getTop();
                int childBottom = listLoc[1] + child.getBottom();

                if (childBottom > visibleTop && childTop < visibleBottom) {
                    if ("related".equals(currentTab)) {
                        relatedAdapter.loadImagesForView(child);
                    } else {
                        commentsAdapter.loadImagesForView(child);
                    }
                }
            }
        }
    }

    private void resetVideo() {
        cancelVideoTimeout();
        if (videoView != null) {
            videoView.stopPlayback();
            videoView.setVideoURI(null);
            videoView.setMediaController(null);
        }
        videoPlaying = false;
        videoPrepared = false;
        videoUrl = null;
    }

    private void restoreVideoUI() {
        LinearLayout loading = (LinearLayout) findViewById(R.id.video_loading);
        if (loading != null) loading.setVisibility(View.GONE);
        if (play != null) play.setVisibility(View.VISIBLE);
        if (thumbnail != null) thumbnail.setVisibility(View.VISIBLE);
    }

    private void hideSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            if (NotPipe.SDK >= 14) {
                int flags = 1 | 2 | 4;
                if (NotPipe.SDK >= 16) {
                    flags |= 256 | 512 | 1024;
                }
                if (NotPipe.SDK >= 19) {
                    flags |= 2048;
                }
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, flags);
            } else if (NotPipe.SDK >= 11) {
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, 1);
            }
        } catch (Exception ignored) {}
    }

    private void showSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            if (NotPipe.SDK >= 11) {
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, 0);
            }
        } catch (Exception ignored) {}
    }

    private void hideDummyTab() {
        final TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost != null && tabHost.getTabWidget() != null && tabHost.getTabWidget().getChildCount() > 0) {
            tabHost.getTabWidget().post(new Runnable() {
                @Override
                public void run() {
                    View dummyTab = tabHost.getTabWidget().getChildAt(0);
                    dummyTab.setVisibility(View.GONE);
                    ViewGroup.LayoutParams params = dummyTab.getLayoutParams();
                    if (params != null) {
                        params.width = 0;
                        params.height = 0;
                        dummyTab.setLayoutParams(params);
                    }
                }
            });
        }
    }

    private void applyOpenCoreLayoutFix() {
        if (isOpencore && videoView != null) {
            videoView.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.FILL_PARENT
            );
            params.gravity = android.view.Gravity.CENTER;
            videoView.setLayoutParams(params);
            videoView.requestLayout();
        }
    }

    private File getCachedVideoFile(String vId) {
        File sdCard = Environment.getExternalStorageDirectory();
        File dir = new File(sdCard, DIR_VIDEOS);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, vId + ".mp4");
    }

    private void updatePlaybackViaText(String host) {
        TextView playbackInfo = (TextView) findViewById(R.id.playback_info);
        if (playbackInfo != null) playbackInfo.setText(getString(R.string.playback_via, host));
    }

    private void restoreVideoMetadata() {
        if (video == null) return;

        ((TextView) findViewById(R.id.title)).setText(video.title);
        ((TextView) findViewById(R.id.channel_title)).setText(video.channel);
        ((TextView) findViewById(R.id.subscribers)).setText(Utils.formatNumber(context, video.subscribers));
        if (video.likes > 0)
            ((Button) findViewById(R.id.like)).setText(Utils.formatNumber(context, video.likes));
        ((TextView) findViewById(R.id.views)).setText(getString(R.string.views, Utils.formatNumber(context, video.views)) +
                "   " + Utils.formatTimeAgo(context, video.publishedAt));

        ImageLoader.loadImage(video.thumbnail, thumbnail, false);
        ImageLoader.loadImage(video.channelThumbnail, channelThumbnail, false);

        if (videoStream != null) {
            updatePlaybackViaText(videoStream.getHost());
        } else if (isUsingMetadataUrl) {
            updatePlaybackViaText(api.getHost());
        }
    }

    private void attachVideoListeners() {
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(final MediaPlayer mp) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cancelVideoTimeout();
                        streamRetryCount = 0;
                        restoreVideoUI();
                        thumbnail.setVisibility(View.INVISIBLE); // Keep it INVISIBLE to maintain bounding box size
                        play.setVisibility(View.GONE);

                        videoPrepared = true;
                        if (videoPosition > 0) {
                            videoView.seekTo(videoPosition);
                        }

                        AspectRatioVideoView arvv = (AspectRatioVideoView) videoView;
                        arvv.setVideoDimensions(mp.getVideoWidth(), mp.getVideoHeight());

                        MediaController mc = new MediaController(context);
                        mc.setAnchorView(videoFrame);
                        videoView.setMediaController(mc);

                        if (isOpencore) {
                            videoView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                                    videoView.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (videoPlaying) mp.start();
                                        }
                                    }, 200);
                                }
                            }, 100);
                        } else {
                            if (videoPlaying) mp.start();
                        }
                    }
                });
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                cancelVideoTimeout();
                videoPlaying = false;
                videoPosition = 0;
            }
        });

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, final int what, int extra) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cancelVideoTimeout();
                        if (what == 43 || what == -11) return; // Ignore certain framework bugs

                        resetVideo();
                        boolean isOffline = !Utils.hasConnection(context);

                        Object instanceToRemove = isUsingMetadataUrl ? api : videoStream;
                        if (instanceToRemove != null && !isOffline) {
                            Manager.getInstance().removeDeadInstance(instanceToRemove);
                        }
                        isUsingMetadataUrl = false;

                        if (isOffline || streamRetryCount < MAX_STREAM_RETRIES) {
                            if (!isOffline) streamRetryCount++;
                            findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                            resolveStreamTask = new ResolveStreamTask(null);
                            resolveStreamTask.execute(videoId);
                        } else {
                            restoreVideoUI();
                            Toast.makeText(context, "Stream failed after multiple attempts.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                return true;
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("videoId", videoId);

        // Save the exact position and actual playing state before destroying layout bounds
        if (videoView != null && videoPrepared) {
            try {
                int pos = videoView.getCurrentPosition();
                if (pos > 0) {
                    videoPosition = pos;
                }
                if (videoView.isPlaying()) {
                    videoPlaying = true;
                }
            } catch (Exception ignored) {}
        }

        outState.putInt("videoPosition", videoPosition);
        outState.putBoolean("videoPlaying", videoPlaying);
        outState.putBoolean("videoPrepared", videoPrepared);
        outState.putBoolean("isUsingMetadataUrl", isUsingMetadataUrl);
        outState.putBoolean("isTabletFullscreen", isTabletFullscreen);
        outState.putSerializable("resolvedChannelIcons", resolvedChannelIcons);
        if (video != null) {
            outState.putString("videoUrl", videoUrl);
            outState.putString("title", video.title);
            outState.putString("channel", video.channel);
            outState.putString("channelThumbnail", video.channelThumbnail);
            outState.putString("thumbnail", video.thumbnail);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        videoId = savedInstanceState.getString("videoId");
        videoPosition = savedInstanceState.getInt("videoPosition");
        videoPlaying = savedInstanceState.getBoolean("videoPlaying");
        videoPrepared = savedInstanceState.getBoolean("videoPrepared");
        isUsingMetadataUrl = savedInstanceState.getBoolean("isUsingMetadataUrl", false);
        videoUrl = savedInstanceState.getString("videoUrl");
        isTabletFullscreen = savedInstanceState.getBoolean("isTabletFullscreen", false);

        if (savedInstanceState.containsKey("resolvedChannelIcons")) {
            Serializable savedIcons = savedInstanceState.getSerializable("resolvedChannelIcons");
            if (savedIcons instanceof Hashtable) {
                resolvedChannelIcons = (Hashtable<String, String>) savedIcons;
            }
        }

        if (isTabletFullscreen && isTablet()) {
            enterFullscreenMode();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            if (isTablet() && isTabletFullscreen) {
                exitFullscreenMode();
                isTabletFullscreen = false;
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
        scrollHandler.removeCallbacks(scrollCheckRunnable);
        cancelVideoTimeout();
        ImageLoader.clearCache();

        if (channelIconExecutor != null) {
            channelIconExecutor.shutdownNow();
        }

        // Cancel all running AsyncTasks to stop background operations
        if (loadVideoTask != null) loadVideoTask.cancel(true);
        if (resolveStreamTask != null) resolveStreamTask.cancel(true);
        if (downloadVideoTask != null) downloadVideoTask.cancel(true);
        if (convertVideoTask != null) convertVideoTask.cancel(true);
        if (loadCommentsTask != null) loadCommentsTask.cancel(true);
        if (loadRelatedTask != null) loadRelatedTask.cancel(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) {
            try {
                if (videoPrepared) {
                    int pos = videoView.getCurrentPosition();
                    if (pos > 0) {
                        videoPosition = pos;
                    }
                    if (videoView.isPlaying()) {
                        videoPlaying = true;
                        videoView.pause();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (videoView != null && videoUrl != null) {
            videoView.stopPlayback();
            isVideoViewNeedsReload = true;
            videoPrepared = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoUrl != null && !config.isUseExternalPlayer()) {
            if (config.isStreamPlayback() || videoUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || videoUrl.startsWith("file://")) {
                if (isVideoViewNeedsReload) {
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                    if (play != null) play.setVisibility(View.GONE);
                    if (thumbnail != null) thumbnail.setVisibility(View.INVISIBLE);
                    applyOpenCoreLayoutFix();
                    videoView.setVisibility(View.VISIBLE);
                    attachVideoListeners();
                    loadVideoUri(videoUrl);
                    isVideoViewNeedsReload = false;
                }
            }
        }
        if (relatedLoaded) {
            android.view.ViewGroup relatedViewGroup = (android.view.ViewGroup) relatedList;
            for (int i = 0; i < relatedViewGroup.getChildCount(); i++) {
                relatedAdapter.resetImageView(relatedViewGroup.getChildAt(i));
            }
            checkVisibleItems();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    private boolean isTablet() {
        return getResources().getBoolean(R.bool.is_tablet);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isTablet()) {
            scrollHandler.removeCallbacks(scrollCheckRunnable);
            boolean wasVideoPlaying = videoPlaying;
            String savedVideoUrl = videoUrl;
            boolean savedIsUsingMetadataUrl = isUsingMetadataUrl;
            int savedVideoPosition = videoPosition;
            if (videoView != null && videoPrepared) {
                try {
                    if (videoView.isPlaying()) {
                        wasVideoPlaying = true;
                    }
                    int pos = videoView.getCurrentPosition();
                    if (pos > 0) {
                        savedVideoPosition = pos;
                    }
                } catch (Exception ignored) {}
            }
            videoPosition = savedVideoPosition;
            int videoLayoutVis = videoLayout != null ? videoLayout.getVisibility() : View.GONE;
            int loadingVis = findViewById(R.id.loading).getVisibility();
            int thumbVis = thumbnail != null ? thumbnail.getVisibility() : View.VISIBLE;
            int playVis = play != null ? play.getVisibility() : View.VISIBLE;
            int videoLoadingVis = findViewById(R.id.video_loading).getVisibility();
            int relatedLoadingVis = relatedLoading != null ? relatedLoading.getVisibility() : View.VISIBLE;
            int commentsLoadingVis = commentsLoading != null ? commentsLoading.getVisibility() : View.VISIBLE;
            setContentView(R.layout.activity_video);
            setupViewReferences();
            setupAdapters();
            setupClickListeners();
            setupScrollHandler();
            View tabHostView = findViewById(android.R.id.tabhost);
            if (tabHostView != null) {
                if (video != null) {
                    tabHostView.setVisibility(View.VISIBLE);
                    setupTabHost();
                } else {
                    tabHostView.setVisibility(View.GONE);
                }
            }

            videoLayout.setVisibility(videoLayoutVis);
            findViewById(R.id.loading).setVisibility(loadingVis);
            thumbnail.setVisibility(thumbVis);
            play.setVisibility(playVis);
            findViewById(R.id.video_loading).setVisibility(videoLoadingVis);
            relatedLoading.setVisibility(relatedLoadingVis);
            commentsLoading.setVisibility(commentsLoadingVis);
            restoreVideoMetadata();
            if (savedVideoUrl != null) {
                videoPrepared = false;
                videoPlaying = wasVideoPlaying;
                videoUrl = savedVideoUrl;
                isUsingMetadataUrl = savedIsUsingMetadataUrl;
                if (config.isStreamPlayback() || videoUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || videoUrl.startsWith("file://")) {
                    applyOpenCoreLayoutFix();
                    videoView.setVisibility(View.VISIBLE);
                    play.setVisibility(View.GONE);
                    thumbnail.setVisibility(View.INVISIBLE);
                    attachVideoListeners();
                    loadVideoUri(savedVideoUrl);
                    isVideoViewNeedsReload = false;
                }
            }
        }

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
            enterFullscreenMode();
        else {
            isTabletFullscreen = false;
            exitFullscreenMode();
        }
    }

    private void toggleActionBar(boolean show) {
        if (NotPipe.SDK >= 11) {
            try {
                Object actionBar = Activity.class.getMethod("getActionBar").invoke(this);
                if (actionBar != null) {
                    actionBar.getClass().getMethod(show ? "show" : "hide").invoke(actionBar);
                }
            } catch (Exception ignored) {}
        }
        try {
            View titleView = getWindow().findViewById(android.R.id.title);
            if (titleView != null) {
                titleView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (titleView.getParent() instanceof View) {
                    ((View) titleView.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Exception ignored) {}
    }

    private void enterFullscreenMode() {
        if (isTablet() && !isTabletFullscreen) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(false);

        if (isTabletFullscreen) {
            hideSystemUI();
            systemUiHandler.removeCallbacks(hideSystemUiRunnable);
            systemUiHandler.postDelayed(hideSystemUiRunnable, 5000);
        }

        if (scrollView != null) scrollView.setVisibility(View.GONE);
        if (videoFrame != null) videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 1.0f));

        // Hide the right-side TabHost panel for tablets
        if (isTablet() && isTabletFullscreen) {
            View tabHost = findViewById(android.R.id.tabhost);
            if (tabHost != null && tabHost.getParent() instanceof View) {
                ((View) tabHost.getParent()).setVisibility(View.GONE);
            }
        }

        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT);
        videoParams.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(videoParams);
        ((AspectRatioVideoView) videoView).setFullscreen(true);
    }

    private void exitFullscreenMode() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(true);
        if (isTablet() && !isTabletFullscreen) return;
        showSystemUI();
        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
        if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
        if (videoFrame != null) videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (isTablet()) {
            View tabHost = findViewById(android.R.id.tabhost);
            if (tabHost != null && tabHost.getParent() instanceof View) {
                ((View) tabHost.getParent()).setVisibility(View.VISIBLE);
            }
            hideDummyTab();
        }
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT);
        videoParams.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(videoParams);
        if (isOpencore) {
            videoView.post(new Runnable() {
                @Override
                public void run() {
                    videoView.requestLayout();
                    videoView.invalidate();
                    ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                }
            });
        }
        ((AspectRatioVideoView) videoView).setFullscreen(false);
    }

    private void setupViewReferences() {
        videoView = (VideoView) findViewById(R.id.video);
        videoLayout = (LinearLayout) findViewById(R.id.video_layout);
        videoFrame = (FrameLayout) findViewById(R.id.video_frame);
        thumbnail = (ImageView) findViewById(R.id.thumbnail);
        channelThumbnail = (ImageView) findViewById(R.id.channel_thumbnail);
        play = (ImageView) findViewById(R.id.play);
        relatedList = findViewById(R.id.related_list);
        commentsList = findViewById(R.id.comments_list);
        relatedLoading = (ProgressBar) findViewById(R.id.related_loading);
        commentsLoading = (ProgressBar) findViewById(R.id.comments_loading);
        scrollView = (ScrollView) findViewById(R.id.scroll_view);
        tabsScrollView = (ScrollView) findViewById(R.id.tabs_scroll_view);
    }

    private void setupAdapters() {
        if (relatedList instanceof android.widget.ListView) {
            android.widget.ListView lv = (android.widget.ListView) relatedList;
            lv.setAdapter(relatedAdapter);
            lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    handleVideoClick(position);
                }
            });
        } else if (relatedList instanceof AdapterLinearLayout) {
            AdapterLinearLayout all = (AdapterLinearLayout) relatedList;
            all.setAdapter(relatedAdapter);
            all.setOnItemClickListener(new AdapterLinearLayout.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterLinearLayout parent, View view, int position, long id) {
                    handleVideoClick(position);
                }
            });
        }

        if (commentsList instanceof android.widget.ListView) {
            ((android.widget.ListView) commentsList).setAdapter(commentsAdapter);
        } else if (commentsList instanceof AdapterLinearLayout) {
            ((AdapterLinearLayout) commentsList).setAdapter(commentsAdapter);
        }
    }

    private void setupClickListeners() {
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(Intent.createChooser(
                            new Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, "https://youtu.be/" + videoId)
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, video != null ? video.title : ""),
                            getString(R.string.share)));
                } catch (android.content.ActivityNotFoundException ignored) {}
            }
        });

        TextView playbackInfo = (TextView) findViewById(R.id.playback_info);
        playbackInfo.setText(getString(R.string.playback_via, getString(R.string.loading_)));
        playbackInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Manager.InstanceInfo> instances = Manager.getInstance().videoInstancesInfo();
                if (instances.isEmpty()) return;

                final List<Object> dialogItems = new ArrayList<Object>();
                String currentApiType = "";
                for (int i = 0; i < instances.size(); i++) {
                    Manager.InstanceInfo info = instances.get(i);
                    if (!info.name.equals(currentApiType)) {
                        currentApiType = info.name;
                        dialogItems.add(currentApiType);
                    }
                    dialogItems.add(info);
                }

                BaseAdapter adapter = new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return dialogItems.size();
                    }

                    @Override
                    public Object getItem(int position) {
                        return dialogItems.get(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public boolean isEnabled(int position) {
                        return !(dialogItems.get(position) instanceof String);
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        Object item = dialogItems.get(position);
                        TextView textView = new TextView(context);
                        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
                        if (item instanceof String) {
                            textView.setText((String) item);
                            textView.setPadding(padding, padding, padding, padding / 2);
                            textView.setTypeface(null, Typeface.BOLD);
                            textView.setTextColor(Color.WHITE);
                            textView.setBackgroundColor(Color.DKGRAY);
                            textView.setTextSize(12);
                        } else {
                            Manager.InstanceInfo info = (Manager.InstanceInfo) item;
                            textView.setText(info.host);
                            textView.setPadding(padding * 2, padding, padding, padding);
                            textView.setTextSize(16);
                            if (NotPipe.SDK < 11) textView.setTextColor(Color.BLACK);
                        }
                        return textView;
                    }
                };

                new AlertDialog.Builder(context)
                        .setTitle(R.string.select_ins)
                        .setAdapter(adapter, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Object selected = dialogItems.get(which);
                                if (selected instanceof Manager.InstanceInfo) {
                                    Manager.InstanceInfo info = (Manager.InstanceInfo) selected;
                                    videoStream = info.instance;
                                    updatePlaybackViaText(info.host);

                                    if (!config.isUseExternalPlayer()) {
                                        thumbnail.setVisibility(View.INVISIBLE);
                                        play.setVisibility(View.GONE);
                                    }
                                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);

                                    if (videoView != null && videoPlaying && !config.isUseExternalPlayer()) {
                                        resetVideo();
                                    }
                                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                                    resolveStreamTask = new ResolveStreamTask(info.instance);
                                    resolveStreamTask.execute(videoId);
                                }
                            }
                        }).show();
            }
        });

        findViewById(R.id.video_meta).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (video == null) return;
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(15, 15, 15, 15);

                TextView viewsAndDate = new TextView(context);
                viewsAndDate.setText(getString(R.string.views, NumberFormat.getNumberInstance().format(video.views))
                        + "   " + DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(video.publishedAt));
                viewsAndDate.setTypeface(null, Typeface.BOLD);
                viewsAndDate.setPadding(0, 0, 0, 10);
                layout.addView(viewsAndDate);

                TextView desc = new TextView(context);
                desc.setText(video.description);
                desc.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                layout.addView(desc);

                ScrollView scroll = new ScrollView(context);
                scroll.addView(layout);

                new AlertDialog.Builder(context).setTitle(R.string.desc).setView(scroll).show();
            }
        });

        findViewById(R.id.channel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (video == null) return;
                Intent intent = new Intent(VideoActivity.this, ChannelActivity.class);
                intent.putExtra("ID", video.channelId);
                startActivity(intent);
                ImageLoader.clearCache();
                System.gc();
            }
        });

        if (video != null && thumbnail != null) {
            View.OnClickListener playVideo = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    streamRetryCount = 0;
                    if (!config.isUseExternalPlayer()) {
                        thumbnail.setVisibility(View.INVISIBLE);
                        play.setVisibility(View.GONE);
                    }
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            final String quality = config.getPreferredQuality();
                            if (config.isConvertVideos() || !"360".equals(quality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                                isUsingMetadataUrl = false;
                                resolveStreamTask = new ResolveStreamTask(null);
                                resolveStreamTask.execute(videoId);
                            } else {
                                isUsingMetadataUrl = true;
                                videoUrl = video.videoUrl;
                                proceedPlay(videoUrl);
                            }
                        }
                    });
                }
            };
            thumbnail.setOnClickListener(playVideo);
            play.setOnClickListener(playVideo);
        }

        View fullScreenBtn = findViewById(R.id.full_screen);
        if (fullScreenBtn != null) {
            fullScreenBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    isTabletFullscreen = true;
                    enterFullscreenMode();
                }
            });
        }

        if (videoFrame != null) {
            videoFrame.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (isTablet() && isTabletFullscreen && event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        showSystemUI();
                        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
                        systemUiHandler.postDelayed(hideSystemUiRunnable, 5000);
                    }
                    return false;
                }
            });
        }
    }

    private void setupTabHost() {
        final TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost == null) return;
        if (tabHost.getTabWidget() != null && tabHost.getTabWidget().getChildCount() > 0) {
            tabHost.clearAllTabs();
        }
        tabHost.setup();
        // Android Holo has a bug where the first tab gets randomly removed in landscape orientation. It does not get removed when the user enters and
        // exits full screen mode, and it isn't present in normal non-tablet UI, so we use a dummy tab as a tablet workaround
        if (isTablet() && NotPipe.SDK >= 11) {
            tabHost.addTab(tabHost.newTabSpec("dummy").setIndicator("").setContent(new TabHost.TabContentFactory() {
                public View createTabContent(String tag) {
                    return new View(VideoActivity.this);
                }
            }));
        }
        tabHost.addTab(tabHost.newTabSpec("related").setIndicator(getString(R.string.related)).setContent(R.id.related));
        tabHost.addTab(tabHost.newTabSpec("comments").setIndicator(getString(R.string.comments)).setContent(R.id.comments));
        TabWidget widget = tabHost.getTabWidget();
        if (widget != null) {
            if (isTablet() && NotPipe.SDK >= 11) {
                hideDummyTab();
            } else if (NotPipe.SDK < 11) {
                // Android <3.0 do not support tabs without icons, which causes them to be too tall. This can be fixed programmatically as follows
                int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 33f, getResources().getDisplayMetrics());
                for (int i = 0; i < widget.getChildCount(); i++) {
                    View child = widget.getChildAt(i);
                    if (child != null && child.getLayoutParams() != null) {
                        child.getLayoutParams().height = height;
                    }
                }
            }
        }
        tabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            public void onTabChanged(String tabId) {
                if (tabId.equals("related")) loadRelatedVideos();
                else /*if (tabId.equals("comments"))*/ loadComments();
                Runnable layoutFix = new Runnable() {
                    @Override
                    public void run() {
                        if (scrollView != null) {
                            scrollView.requestLayout();
                            scrollView.invalidate();
                        }
                        if (tabsScrollView != null) {
                            tabsScrollView.requestLayout();
                            tabsScrollView.invalidate();
                        }
                        checkVisibleItems();
                    }
                };
                if (tabsScrollView != null) {
                    tabsScrollView.post(layoutFix);
                } else if (scrollView != null) {
                    scrollView.post(layoutFix);
                }
            }
        });
        tabHost.setCurrentTabByTag("related");
    }

    private void setupScrollHandler() {
        scrollHandler.post(scrollCheckRunnable);
    }

    private android.os.Handler videoTimeoutHandler = new android.os.Handler();
    private Runnable videoTimeoutRunnable = null;

    private void setupVideoTimeout() {
        cancelVideoTimeout();
        videoTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                final boolean isOffline = !Utils.hasConnection(context);
                Object instanceToRemove = isUsingMetadataUrl ? api : videoStream;
                if (instanceToRemove != null && !isOffline) {
                    Manager.getInstance().removeDeadInstance(instanceToRemove);
                }
                isUsingMetadataUrl = false;
                resetVideo();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isOffline || streamRetryCount < MAX_STREAM_RETRIES) {
                            if (!isOffline) streamRetryCount++;

                            findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                            thumbnail.setVisibility(View.INVISIBLE);
                            play.setVisibility(View.GONE);
                            resolveStreamTask = new ResolveStreamTask(null);
                            resolveStreamTask.execute(videoId);
                        } else {
                            restoreVideoUI();
                            Toast.makeText(context, "Video stream timed out after multiple attempts.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        };
        videoTimeoutHandler.postDelayed(videoTimeoutRunnable, VIDEO_BUFFER_TIMEOUT);
    }

    private void cancelVideoTimeout() {
        if (videoTimeoutRunnable != null) {
            videoTimeoutHandler.removeCallbacks(videoTimeoutRunnable);
            videoTimeoutRunnable = null;
        }
    }

    private void loadRelatedVideos() {
        if (relatedLoaded || video == null) return;
        if (video.related != null && !video.related.isEmpty()) {
            relatedVideos.clear();
            relatedVideos.addAll(video.related);
            relatedAdapter.notifyDataSetChanged();
            relatedLoading.setVisibility(View.GONE);
            relatedLoaded = true;
        } else {
            if (loadRelatedTask != null && loadRelatedTask.getStatus() != AsyncTask.Status.FINISHED) return;
            relatedLoading.setVisibility(View.VISIBLE); // Reveal spinner on retry
            loadRelatedTask = new LoadRelatedTask();
            loadRelatedTask.execute(videoId);
        }
    }

    private void loadComments() {
        if (commentsLoaded || video == null) return;
        if (video.comments != null && !video.comments.isEmpty()) {
            comments.clear();
            comments.addAll(video.comments);
            commentsAdapter.notifyDataSetChanged();
            commentsLoading.setVisibility(View.GONE);
            commentsLoaded = true;
        } else {
            if (loadCommentsTask != null && loadCommentsTask.getStatus() != AsyncTask.Status.FINISHED) return;
            commentsLoading.setVisibility(View.VISIBLE); // Reveal spinner on retry
            loadCommentsTask = new LoadCommentsTask();
            loadCommentsTask.execute(videoId);
        }
    }

    private void executeAsyncSetVideoUri(final String targetUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean needsFallback = false;
                try {
                    videoView.setVideoURI(Uri.parse(targetUrl));
                } catch (RuntimeException e) {
                    if (!e.getClass().getSimpleName().contains("CalledFromWrongThreadException")) {
                        needsFallback = true;
                    }
                } catch (Exception e) {
                    needsFallback = true;
                }

                if (needsFallback) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                videoView.setVideoURI(Uri.parse(targetUrl));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            videoView.requestFocus(0);
                            setupVideoTimeout();
                            videoView.requestLayout();
                            videoView.invalidate();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            videoView.requestFocus(0);
                            setupVideoTimeout();
                            videoView.requestLayout();
                            videoView.invalidate();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadVideoUri(final String targetUrl) {
        if (config.isAsyncSetVideoUri()) {
            boolean surfaceReady = false;
            try {
                android.view.Surface surface = videoView.getHolder().getSurface();
                if (surface != null) {
                    if (NotPipe.SDK >= 9) {
                        surfaceReady = (Boolean) surface.getClass().getMethod("isValid").invoke(surface);
                    } else {
                        surfaceReady = true; // For archaic devices, assuming non-null surface is ready
                    }
                }
            } catch (Exception ignored) {}

            if (surfaceReady) {
                // Surface is fully ready right now, safe to run async
                executeAsyncSetVideoUri(targetUrl);
            } else {
                // If the surface isn't ready, the async thread will blast through setVideoURI in 10ms
                // and defer the actual heavy loading to the UI thread's surfaceCreated callback (freezing it).
                // Instead, we wait for surfaceCreated, then run our async task to ensure the background thread catches the load.
                videoView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(android.view.SurfaceHolder holder) {
                        videoView.getHolder().removeCallback(this);
                        // Post inside UI queue to guarantee VideoView's own surfaceCreated finishes first
                        videoView.post(new Runnable() {
                            @Override
                            public void run() {
                                executeAsyncSetVideoUri(targetUrl);
                            }
                        });
                    }

                    @Override
                    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}

                    @Override
                    public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
                });
            }
        } else {
            videoView.setVideoURI(Uri.parse(targetUrl));
            videoView.requestFocus(0);
            setupVideoTimeout();
            videoView.requestLayout();
            videoView.invalidate();
        }
    }

    private class LoadVideoTask extends AsyncTask<String, Void, Video> {
        @Override
        protected Video doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getVideo(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Video fetchedVideo) {
            if (isCancelled()) return;
            if (fetchedVideo == null) {
                findViewById(R.id.loading).setVisibility(View.GONE);
                Toast.makeText(context, R.string.metadata_fail, Toast.LENGTH_SHORT).show();
                return;
            }
            video = fetchedVideo;
            if (video.channelId != null && video.channelThumbnail != null && video.channelThumbnail.length() > 0)
                resolvedChannelIcons.put(video.channelId, video.channelThumbnail);
            findViewById(R.id.loading).setVisibility(View.GONE);
            videoLayout.setVisibility(View.VISIBLE);

            ((TextView) findViewById(R.id.title)).setText(video.title);
            ((TextView) findViewById(R.id.channel_title)).setText(video.channel);
            ((TextView) findViewById(R.id.subscribers)).setText(Utils.formatNumber(context, video.subscribers));
            if (video.likes > 0)
                ((Button) findViewById(R.id.like)).setText(Utils.formatNumber(context, video.likes));
            ((TextView) findViewById(R.id.views)).setText(getString(R.string.views, Utils.formatNumber(context, video.views)) +
                    "   " + Utils.formatTimeAgo(context, video.publishedAt));

            final String quality = config.getPreferredQuality();
            if (config.isConvertVideos() || !"360".equals(quality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                List<VideoStream> targetList = "360".equals(quality) ? Manager.getInstance().getVideoInstances() : Manager.getInstance().getHqInstances();
                if (config.isConvertVideos()) {
                    List<VideoStream> ytApiLegacy = new ArrayList<VideoStream>();
                    for (int i = 0; i < targetList.size(); i++) {
                        if (targetList.get(i) instanceof YtApiLegacy)
                            ytApiLegacy.add(targetList.get(i));
                    }
                    if (!ytApiLegacy.isEmpty()) targetList = ytApiLegacy;
                }
                if (!targetList.isEmpty()) {
                    videoStream = targetList.get(new Random().nextInt(targetList.size()));
                    updatePlaybackViaText(videoStream.getHost());
                }
            } else {
                isUsingMetadataUrl = true;
                videoStream = null;
                updatePlaybackViaText(api.getHost());
            }

            View.OnClickListener playVideo = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    streamRetryCount = 0;
                    if (!config.isUseExternalPlayer()) {
                        thumbnail.setVisibility(View.INVISIBLE);
                        play.setVisibility(View.GONE);
                    }
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (config.isConvertVideos() || !"360".equals(quality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                                isUsingMetadataUrl = false;
                                resolveStreamTask = new ResolveStreamTask(null);
                                resolveStreamTask.execute(videoId);
                            } else {
                                isUsingMetadataUrl = true;
                                videoUrl = video.videoUrl;
                                proceedPlay(videoUrl);
                            }
                        }
                    });
                }
            };

            ImageLoader.loadImage(video.thumbnail, thumbnail, false);
            ImageLoader.loadImage(video.channelThumbnail, channelThumbnail, false);
            thumbnail.setOnClickListener(playVideo);
            play.setOnClickListener(playVideo);

            // Now that the main video is completely loaded, reveal and setup the tabs side-panel
            View tabHostView = findViewById(android.R.id.tabhost);
            if (tabHostView != null) {
                tabHostView.setVisibility(View.VISIBLE);
            }

            loadRelatedVideos();
        }
    }

    private class ResolveStreamTask extends AsyncTask<String, Void, String> {
        private VideoStream targetInstance;
        private VideoStream[] successInstance = new VideoStream[1];
        private String errorMessage;
        private boolean isFileNotFound;
        private String requestedQuality;
        private String taskQuality;

        ResolveStreamTask(VideoStream targetInstance) {
            this.targetInstance = targetInstance;
        }

        ResolveStreamTask(VideoStream targetInstance, String quality) {
            this.targetInstance = targetInstance;
            this.taskQuality = quality;
        }

        @Override
        protected void onPreExecute() {
            if (targetInstance == null) {
                // Reuse the instance already picked by LoadVideoTask
                if (videoStream == null) {
                    String quality = taskQuality != null ? taskQuality : config.getPreferredQuality();
                    List<VideoStream> targetList = "360".equals(quality) ? Manager.getInstance().getVideoInstances() : Manager.getInstance().getHqInstances();
                    if (config.isConvertVideos()) {
                        List<VideoStream> ytApiLegacy = new ArrayList<VideoStream>();
                        for (int i = 0; i < targetList.size(); i++) {
                            if (targetList.get(i) instanceof YtApiLegacy)
                                ytApiLegacy.add(targetList.get(i));
                        }
                        if (!ytApiLegacy.isEmpty()) targetList = ytApiLegacy;
                    }
                    if (!targetList.isEmpty()) {
                        videoStream = targetList.get(new Random().nextInt(targetList.size()));
                    }
                }
            } else {
                videoStream = targetInstance;
                updatePlaybackViaText(targetInstance.getHost());
            }
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                Utils.waitForConnection(context);
            } catch (IOException e) {
                return null;
            }

            try {
                String id = params[0];
                if (isCancelled()) return null;
                File cachedVideo = getCachedVideoFile(id);
                if (cachedVideo.exists()) return cachedVideo.getAbsolutePath();
                String quality = params.length > 1 ? params[1] : config.getPreferredQuality();
                requestedQuality = quality;
                if (targetInstance != null) {
                    if (config.isConvertVideos() && targetInstance instanceof YtApiLegacy) {
                        if (isCancelled()) return null;
                        return ((YtApiLegacy) targetInstance).getConvUrl(id, config.getConvertCodec());
                    }
                    if (isCancelled()) return null;
                    return targetInstance.getVideoUrl(id, quality);
                }
                if (isCancelled()) return null;
                if (config.isConvertVideos()) {
                    return Manager.getInstance().getVideoUrl(id, quality, config.getConvertCodec(), videoStream, successInstance);
                }
                return Manager.getInstance().getVideoUrl(id, quality, videoStream, successInstance);
            } catch (java.io.FileNotFoundException e) {
                isFileNotFound = true;
                errorMessage = e.getMessage();
                return null;
            } catch (Exception e) {
                errorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultUrl) {
            if (isCancelled()) return;
            isUsingMetadataUrl = false;

            if (resultUrl != null) {
                // DELETE THIS LINE: videoUrl = resultUrl;
                if (successInstance[0] != null) {
                    videoStream = successInstance[0];
                }
                if (resultUrl.startsWith(Environment.getExternalStorageDirectory().getPath())) {
                    updatePlaybackViaText(getString(R.string.cache));
                    proceedPlay(resultUrl);
                } else {
                    if (videoStream != null) updatePlaybackViaText(videoStream.getHost());
                    TextView progressView = (TextView) findViewById(R.id.video_progress);
                    if (config.isConvertVideos() && resultUrl.contains("&codec=")) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (progressView != null) {
                            progressView.setText(R.string.conv_long);
                            progressView.setVisibility(View.VISIBLE);
                        }
                        proceedPlay(resultUrl);
                    } else if (config.isConvertVideos()) {
                        if (progressView != null) {
                            progressView.setText(getString(R.string.dvauha_msg, getString(R.string.loading)));
                            progressView.setVisibility(View.VISIBLE);
                        }
                        convertVideoTask = new ConvertVideoTask();
                        convertVideoTask.execute(resultUrl);
                    } else {
                        proceedPlay(resultUrl);
                    }
                }
            } else if (isFileNotFound) {
                if ("360".equals(requestedQuality)) {
                    resetVideo();
                    restoreVideoUI();
                    if (targetInstance != null) {
                        Manager.getInstance().removeDeadInstance(targetInstance);
                    }
                    Toast.makeText(context, "All instances failed to provide video.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.no_quality, Toast.LENGTH_SHORT).show();
                    if (video.videoUrl != null && video.videoUrl.length() > 0) {
                        isUsingMetadataUrl = true;
                        updatePlaybackViaText(api.getHost());
                        proceedPlay(videoUrl);
                    } else {
                        isUsingMetadataUrl = false;
                        resolveStreamTask = new ResolveStreamTask(null, "360");
                        resolveStreamTask.execute(videoId, "360");
                    }
                }
            } else {
                resetVideo();
                restoreVideoUI();
                updatePlaybackViaText(videoStream != null ? videoStream.getHost() : api.getHost());

                if (errorMessage != null) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show();
                } else if (targetInstance != null) {
                    Manager.getInstance().removeDeadInstance(targetInstance);
                    Toast.makeText(context, "Failed to connect to this instance.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Failed to fetch video URL", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void proceedPlay(final String targetUrl) {
        boolean isLocal = targetUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || targetUrl.startsWith("file://");
        boolean forceDownload = config.isConvertVideos() && !isLocal;
        boolean shouldStream = (config.isStreamPlayback() && !forceDownload) || isLocal;

        if (config.isUseExternalPlayer()) {
            if (shouldStream) {
                findViewById(R.id.video_loading).setVisibility(View.GONE);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.setDataAndType(Uri.parse(targetUrl), "video/mp4");
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                downloadVideoTask = new DownloadVideoTask();
                downloadVideoTask.execute(targetUrl);
            }
        } else {
            resetVideo();
            videoPlaying = true;
            applyOpenCoreLayoutFix();
            attachVideoListeners();

            if (shouldStream) {
                videoUrl = targetUrl;
                videoView.setVisibility(View.VISIBLE);
                loadVideoUri(targetUrl);
            } else {
                videoUrl = null;
                downloadVideoTask = new DownloadVideoTask();
                downloadVideoTask.execute(targetUrl);
            }
        }
    }

    private class DownloadVideoTask extends AsyncTask<String, Integer, File> {
        private TextView progressView;
        private boolean sdCardNotMounted = false;
        private boolean noSpaceError = false;

        @Override
        protected void onPreExecute() {
            progressView = (TextView) findViewById(R.id.video_progress);
            progressView.setVisibility(View.VISIBLE);
        }

        @Override
        protected File doInBackground(String... params) {
            try {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    sdCardNotMounted = true;
                    return null;
                }
                File sdCard = Environment.getExternalStorageDirectory();
                android.os.StatFs stat = new android.os.StatFs(sdCard.getPath());
                long availableSpace = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
                if (availableSpace < 150L * 1024L * 1024L) { // 150 Megabytes
                    noSpaceError = true;
                    return null;
                }

                String downloadUrl = params[0];
                File videoFile = getCachedVideoFile(videoId);
                if (!videoFile.exists()) {
                    HttpClient.downloadToFile(downloadUrl, videoFile.getAbsolutePath(), new HttpClient.DownloadProgressListener() {
                        private long lastUpdateTime = 0;
                        @Override
                        public void onProgress(final long bytesDownloaded, final long totalBytes) {
                            if (isCancelled()) return;
                            if (totalBytes > 0) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastUpdateTime >= 500) {
                                    lastUpdateTime = currentTime;
                                    publishProgress((int) ((bytesDownloaded * 100) / totalBytes));
                                }
                            }
                        }
                    });
                }
                if (isCancelled()) return null;
                return videoFile;
            } catch (Exception e) {
                e.printStackTrace();
                String msg = e.getMessage();
                if (e instanceof IOException && msg != null &&
                        (msg.toLowerCase().contains("no space left") || msg.contains("ENOSPC"))) {
                    noSpaceError = true;
                }
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressView.setText(getString(R.string.percent, values[0]));
        }

        @Override
        protected void onPostExecute(File videoFile) {
            if (isCancelled()) return;
            if (progressView != null) progressView.setVisibility(View.GONE);

            if (sdCardNotMounted) {
                resetVideo(); restoreVideoUI();
                Toast.makeText(context, R.string.sd_card, Toast.LENGTH_LONG).show();
                return;
            } if (noSpaceError) {
                resetVideo(); restoreVideoUI();
                Toast.makeText(context, R.string.sd_card_space, Toast.LENGTH_LONG).show();
                return;
            }

            if (videoFile != null) {
                if (config.isUseExternalPlayer()) {
                    findViewById(R.id.video_loading).setVisibility(View.GONE);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.fromFile(videoFile), "video/mp4");
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    resetVideo();
                    videoUrl = Uri.fromFile(videoFile).toString();
                    videoPlaying = true;
                    applyOpenCoreLayoutFix();
                    attachVideoListeners();

                    videoUrl = Uri.fromFile(videoFile).toString();
                    videoView.setVisibility(View.VISIBLE);
                    loadVideoUri(videoUrl);
                }
            } else {
                boolean isOffline = !Utils.hasConnection(context);
                Object instanceToRemove = isUsingMetadataUrl ? api : videoStream;
                if (instanceToRemove != null && !isOffline) {
                    Manager.getInstance().removeDeadInstance(instanceToRemove);
                }
                isUsingMetadataUrl = false;
                resetVideo();

                if (isOffline || streamRetryCount < MAX_STREAM_RETRIES) {
                    if (!isOffline) streamRetryCount++;
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                    thumbnail.setVisibility(View.INVISIBLE);
                    play.setVisibility(View.GONE);
                    resolveStreamTask = new ResolveStreamTask(null);
                    resolveStreamTask.execute(videoId);
                } else {
                    restoreVideoUI();
                    Toast.makeText(context, "Download failed after multiple attempts.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private class ConvertVideoTask extends AsyncTask<String, String, String> {
        private String targetUrl;
        private Exception convertException;

        @Override
        protected void onPreExecute() {
            TextView progressView = (TextView) findViewById(R.id.video_progress);
            if (progressView != null) {
                progressView.setText(getString(R.string.dvauha_msg, getString(R.string.loading)));
                progressView.setVisibility(View.VISIBLE);
            }
        }

        @Override
        protected String doInBackground(String... params) {
            this.targetUrl = params[0];
            final String[] resultUrl = new String[1];

            try {
                DvaUha.convert(targetUrl, config.getConvertCodec(), new DvaUha.Callback() {
                    @Override
                    public void onMessage(String msg) {
                        if (isCancelled()) return;
                        publishProgress(msg);
                    }

                    @Override
                    public void onSuccess(String url) {
                        resultUrl[0] = url;
                    }

                    @Override
                    public void onError(Exception e) {
                        convertException = e;
                    }

                    @Override
                    public String onCaptchaRequired(final String imageUrl) {
                        if (isCancelled()) return "";
                        final String[] result = new String[]{""};
                        final Object lock = new Object();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                                builder.setTitle(R.string.dvauha);

                                LinearLayout layout = new LinearLayout(context);
                                layout.setOrientation(LinearLayout.VERTICAL);
                                layout.setPadding(15, 15, 15, 15);
                                layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

                                final ImageView imageView = new ImageView(context);
                                imageView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                                imageView.setMinimumHeight(100);
                                layout.addView(imageView);

                                final android.widget.EditText input = new android.widget.EditText(context);
                                input.setSingleLine();
                                input.setHint(R.string.solve_captcha);
                                layout.addView(input);

                                builder.setView(layout);
                                builder.setCancelable(false);
                                builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        result[0] = input.getText().toString();
                                        synchronized (lock) {
                                            lock.notify();
                                        }
                                    }
                                });
                                builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        synchronized (lock) {
                                            lock.notify();
                                        }
                                    }
                                });
                                builder.show();
                                ImageLoader.loadImage(imageUrl, imageView, false);
                            }
                        });
                        synchronized (lock) {
                            try {
                                lock.wait();
                            } catch (InterruptedException ignored) {}
                        }
                        return result[0];
                    }
                });
            } catch (Exception e) {
                convertException = e;
            }
            if (isCancelled()) return null;
            return resultUrl[0];
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (isCancelled()) return;
            TextView progressView = (TextView) findViewById(R.id.video_progress);
            if (progressView != null)
                progressView.setText(getString(R.string.dvauha_msg, values[0]));
        }

        @Override
        protected void onPostExecute(String downloadUrl) {
            if (isCancelled()) return;
            TextView progressView = (TextView) findViewById(R.id.video_progress);
            if (progressView != null) progressView.setVisibility(View.GONE);

            if (downloadUrl != null) {
                proceedPlay(downloadUrl);
            } else {
                resetVideo();
                restoreVideoUI();
                String errorMsg = convertException == null ? "Unknown conversion error" : convertException.getMessage();
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show();
            }
        }
    }

    private class LoadCommentsTask extends AsyncTask<String, Void, List<Comment>> {
        @Override
        protected List<Comment> doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getComments(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Comment> result) {
            if (isCancelled()) return;
            if (result != null) {
                comments.clear();
                comments.addAll(result);
                commentsAdapter.notifyDataSetChanged();
                commentsLoaded = true;
            }
            commentsLoading.setVisibility(View.GONE);

            Runnable layoutFix = new Runnable() {
                @Override
                public void run() {
                    if (scrollView != null) {
                        scrollView.requestLayout();
                        scrollView.invalidate();
                    }
                    if (tabsScrollView != null) {
                        tabsScrollView.requestLayout();
                        tabsScrollView.invalidate();
                    }
                    if (commentsList != null) {
                        commentsList.requestLayout();
                        commentsList.invalidate();
                    }
                }
            };

            if (tabsScrollView != null) {
                tabsScrollView.post(layoutFix);
            } else if (scrollView != null) {
                scrollView.post(layoutFix);
            }
        }
    }

    private class LoadRelatedTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected List<VideoInfo> doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getRelated(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<VideoInfo> result) {
            if (isCancelled()) return;
            if (result != null) {
                relatedVideos.clear();
                relatedVideos.addAll(result);
                relatedAdapter.notifyDataSetChanged();
                relatedLoaded = true;
            }
            relatedLoading.setVisibility(View.GONE);

            Runnable layoutFix = new Runnable() {
                @Override
                public void run() {
                    if (scrollView != null) {
                        scrollView.requestLayout();
                        scrollView.invalidate();
                    }
                    if (tabsScrollView != null) {
                        tabsScrollView.requestLayout();
                        tabsScrollView.invalidate();
                    }
                    if (relatedList != null) {
                        relatedList.requestLayout();
                        relatedList.invalidate();
                    }
                }
            };

            if (tabsScrollView != null) {
                tabsScrollView.post(layoutFix);
            } else if (scrollView != null) {
                scrollView.post(layoutFix);
            }
        }
    }
}