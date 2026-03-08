package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
    AdapterLinearLayout relatedList, commentsList;
    ProgressBar relatedLoading, commentsLoading;
    ScrollView scrollView;

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

    private int videoPosition = 0;
    private boolean videoPlaying = false;
    private boolean videoPrepared = false;
    private String videoUrl = null;
    private boolean isUsingMetadataUrl = false;

    private static final int VIDEO_BUFFER_TIMEOUT = 60000;
    private static final String DIR_VIDEOS = "notPipe/videos";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            getWindow().requestFeature(android.view.Window.FEATURE_NO_TITLE);
        }
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

        videoView = (VideoView) findViewById(R.id.video);
        videoLayout = (LinearLayout) findViewById(R.id.video_layout);
        videoFrame = (FrameLayout) findViewById(R.id.video_frame);
        thumbnail = (ImageView) findViewById(R.id.thumbnail);
        channelThumbnail = (ImageView) findViewById(R.id.channel_thumbnail);
        play = (ImageView) findViewById(R.id.play);

        relatedList = (AdapterLinearLayout) findViewById(R.id.related_list);
        commentsList = (AdapterLinearLayout) findViewById(R.id.comments_list);
        relatedLoading = (ProgressBar) findViewById(R.id.related_loading);
        commentsLoading = (ProgressBar) findViewById(R.id.comments_loading);
        scrollView = (ScrollView) findViewById(R.id.scroll_view);

        relatedAdapter = new VideoAdapter(this, R.layout.video_item, relatedVideos);
        commentsAdapter = new CommentAdapter(this, R.layout.comment_item, comments);
        relatedList.setAdapter(relatedAdapter);
        commentsList.setAdapter(commentsAdapter);

        videoView.setVisibility(View.GONE);
        applyOpenCoreLayoutFix();

        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(Intent.createChooser(
                            new Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, "https://youtu.be/" + videoId)
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, video.title),
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

                                    thumbnail.setVisibility(View.INVISIBLE);
                                    play.setVisibility(View.GONE);
                                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);

                                    if (videoView != null && videoPlaying && !config.isUseExternalPlayer()) {
                                        stopAndResetVideo();
                                    }
                                    new ResolveStreamTask(info.instance).execute(videoId);
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

        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        tabHost.setup();
        tabHost.addTab(tabHost.newTabSpec("related").setIndicator(getString(R.string.related)).setContent(R.id.related));
        tabHost.addTab(tabHost.newTabSpec("comments").setIndicator(getString(R.string.comments)).setContent(R.id.comments));

        if (NotPipe.SDK < 11) {
            int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 33f, getResources().getDisplayMetrics());
            TabWidget widget = tabHost.getTabWidget();
            widget.getChildAt(0).getLayoutParams().height = height;
            widget.getChildAt(1).getLayoutParams().height = height;
        }

        tabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            public void onTabChanged(String tabId) {
                if (tabId.equals("related")) loadRelatedVideos();
                else loadComments();
                // Check immediately on tab switch
                scrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        checkVisibleItems();
                    }
                });
            }
        });

        // Start the legacy polling loop!
        scrollHandler.post(scrollCheckRunnable);

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterFullscreenMode();
        }

        new LoadVideoTask().execute(videoId);
    }

    private int lastScrollY = -1;
    private Handler scrollHandler = new Handler();
    private Runnable scrollCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (scrollView != null) {
                int currentScrollY = scrollView.getScrollY();
                if (currentScrollY != lastScrollY || lastScrollY == -1) {
                    lastScrollY = currentScrollY;
                    checkVisibleItems();
                }
            }
            scrollHandler.postDelayed(this, 100);
        }
    };

    /**
     * Identifies exactly which views are on the screen and loads their images.
     */
    private void checkVisibleItems() {
        if (scrollView == null || scrollView.getHeight() == 0) return;
        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost == null) return;

        String currentTab = tabHost.getCurrentTabTag();
        AdapterLinearLayout activeList = null;

        if ("related".equals(currentTab) && relatedLoaded) {
            activeList = relatedList;
        } else if ("comments".equals(currentTab) && commentsLoaded) {
            activeList = commentsList;
        }

        if (activeList == null || activeList.getChildCount() == 0) return;

        int[] listLoc = new int[2];
        int[] scrollLoc = new int[2];
        activeList.getLocationInWindow(listLoc);
        scrollView.getLocationInWindow(scrollLoc);

        int visibleTop = scrollLoc[1];
        int visibleBottom = visibleTop + scrollView.getHeight();

        for (int i = 0; i < activeList.getChildCount(); i++) {
            View child = activeList.getChildAt(i);
            if (child == null) continue;

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

    private void stopAndResetVideo() {
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

    private void attachVideoListeners() {
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(final MediaPlayer mp) {
                cancelVideoTimeout();
                restoreVideoUI();
                thumbnail.setVisibility(View.INVISIBLE); // Keep it INVISIBLE to maintain bounding box size
                play.setVisibility(View.GONE);

                videoPrepared = true;
                videoPlaying = true;
                videoView.seekTo(videoPosition);

                AspectRatioVideoView arvv = (AspectRatioVideoView) videoView;
                arvv.setVideoDimensions(mp.getVideoWidth(), mp.getVideoHeight());

                if (isOpencore) {
                    videoView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                            videoView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mp.start();
                                }
                            }, 200);
                        }
                    }, 100);
                } else mp.start();
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
            public boolean onError(MediaPlayer mp, int what, int extra) {
                cancelVideoTimeout();
                if (what == 43 || what == -11) return true; // Ignore certain framework bugs

                stopAndResetVideo();

                if (isUsingMetadataUrl) {
                    Manager.getInstance().removeDeadInstance(api);
                    isUsingMetadataUrl = false;
                    Toast.makeText(context, "Stream failed. Trying another instance...", Toast.LENGTH_SHORT).show();
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                    new ResolveStreamTask(null).execute(videoId);
                } else {
                    if (videoStream != null) Manager.getInstance().removeDeadInstance(videoStream);
                    restoreVideoUI();
                    Toast.makeText(context, R.string.try_again, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("videoId", videoId);
        outState.putInt("videoPosition", videoPosition);
        outState.putBoolean("videoPlaying", videoPlaying);
        outState.putBoolean("videoPrepared", videoPrepared);
        outState.putBoolean("isUsingMetadataUrl", isUsingMetadataUrl);
        if (video != null) {
            outState.putString("videoUrl", videoUrl);
            outState.putString("title", video.title);
            outState.putString("channel", video.channel);
            outState.putString("channelThumbnail", video.channelThumbnail);
            outState.putString("thumbnail", video.thumbnail);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        videoId = savedInstanceState.getString("videoId");
        videoPosition = savedInstanceState.getInt("videoPosition");
        videoPlaying = savedInstanceState.getBoolean("videoPlaying");
        videoPrepared = savedInstanceState.getBoolean("videoPrepared");
        isUsingMetadataUrl = savedInstanceState.getBoolean("isUsingMetadataUrl", false);
        videoUrl = savedInstanceState.getString("videoUrl");

        if (videoPrepared && videoUrl != null) {
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                enterFullscreenMode();
            }
            applyOpenCoreLayoutFix();
            videoView.setVisibility(View.VISIBLE);
            play.setVisibility(View.GONE);

            attachVideoListeners();
            videoView.setVideoURI(Uri.parse(videoUrl));
            videoView.setMediaController(new MediaController(context));
            videoView.requestFocus(0);
            setupVideoTimeout();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scrollHandler.removeCallbacks(scrollCheckRunnable);
        cancelVideoTimeout();
        ImageLoader.clearCache();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) enterFullscreenMode();
        else exitFullscreenMode();
    }

    private void toggleActionBar(boolean show) {
        if (NotPipe.SDK >= 11) {
            try {
                Object actionBar = Activity.class.getMethod("getActionBar").invoke(this);
                if (actionBar != null) {
                    actionBar.getClass().getMethod(show ? "show" : "hide").invoke(actionBar);
                }
            } catch (Exception ignored) {
            }
        }
        try {
            View titleView = getWindow().findViewById(android.R.id.title);
            if (titleView != null) {
                titleView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (titleView.getParent() instanceof View) {
                    ((View) titleView.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void enterFullscreenMode() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(false);

        scrollView.setVisibility(View.GONE);
        videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 1.0f));

        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT);
        videoParams.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(videoParams);
        ((AspectRatioVideoView) videoView).setFullscreen(true);
    }

    private void exitFullscreenMode() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(true);

        scrollView.setVisibility(View.VISIBLE);
        videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    private android.os.Handler videoTimeoutHandler = new android.os.Handler();
    private Runnable videoTimeoutRunnable = null;

    private void setupVideoTimeout() {
        cancelVideoTimeout();
        videoTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (isUsingMetadataUrl) {
                    Manager.getInstance().removeDeadInstance(api);
                    isUsingMetadataUrl = false;
                } else if (videoStream != null) {
                    Manager.getInstance().removeDeadInstance(videoStream);
                }
                stopAndResetVideo();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                        thumbnail.setVisibility(View.INVISIBLE);
                        play.setVisibility(View.GONE);
                        Toast.makeText(context, "Video stream timed out. Trying another instance...", Toast.LENGTH_SHORT).show();
                        new ResolveStreamTask(null).execute(videoId);
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
        if (relatedLoaded) return;
        if (video != null && video.related != null && !video.related.isEmpty()) {
            relatedVideos.clear();
            relatedVideos.addAll(video.related);
            relatedAdapter.notifyDataSetChanged();
            relatedLoading.setVisibility(View.GONE);
            relatedLoaded = true;
        } else {
            new LoadRelatedTask().execute(videoId);
        }
    }

    private void loadComments() {
        if (commentsLoaded) return;
        if (video != null && video.comments != null && !video.comments.isEmpty()) {
            comments.clear();
            comments.addAll(video.comments);
            commentsAdapter.notifyDataSetChanged();
            commentsLoading.setVisibility(View.GONE);
            commentsLoaded = true;
        } else {
            new LoadCommentsTask().execute(videoId);
        }
    }

    private class LoadVideoTask extends AsyncTask<String, Void, Video> {
        @Override
        protected Video doInBackground(String... params) {
            try {
                return api.getVideo(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Video fetchedVideo) {
            if (fetchedVideo == null) {
                findViewById(R.id.loading).setVisibility(View.GONE);
                Toast.makeText(context, "Failed to load video details. Try reloading.", Toast.LENGTH_SHORT).show();
                return;
            }
            video = fetchedVideo;
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
                    thumbnail.setVisibility(View.INVISIBLE);
                    play.setVisibility(View.GONE);
                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);

                    if (config.isConvertVideos() || !"360".equals(quality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                        isUsingMetadataUrl = false;
                        new ResolveStreamTask(null).execute(videoId);
                    } else {
                        isUsingMetadataUrl = true;
                        videoUrl = video.videoUrl;
                        proceedPlay(videoUrl);
                    }
                }
            };

            ImageLoader.loadImage(video.thumbnail, thumbnail, false);
            ImageLoader.loadImage(video.channelThumbnail, channelThumbnail, false);
            thumbnail.setOnClickListener(playVideo);
            play.setOnClickListener(playVideo);

            loadRelatedVideos();
        }
    }

    private class ResolveStreamTask extends AsyncTask<String, Void, String> {
        private VideoStream targetInstance;
        private VideoStream[] successInstance = new VideoStream[1];
        private String errorMessage;

        public ResolveStreamTask(VideoStream targetInstance) {
            this.targetInstance = targetInstance;
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                String id = params[0];
                File cachedVideo = getCachedVideoFile(id);
                if (cachedVideo.exists()) return cachedVideo.getAbsolutePath();
                String quality = config.getPreferredQuality();
                if (targetInstance != null) {
                    if (config.isConvertVideos() && targetInstance instanceof YtApiLegacy) {
                        return ((YtApiLegacy) targetInstance).getConvUrl(id, config.getConvertCodec());
                    }
                    return targetInstance.getVideoUrl(id, quality);
                }
                if (config.isConvertVideos()) {
                    return Manager.getInstance().getVideoUrl(id, quality, config.getConvertCodec(), videoStream, successInstance);
                }
                return Manager.getInstance().getVideoUrl(id, quality, videoStream, successInstance);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultUrl) {
            isUsingMetadataUrl = false;

            if (resultUrl != null) {
                videoUrl = resultUrl;
                if (targetInstance != null) {
                    videoStream = targetInstance;
                } else if (successInstance[0] != null) {
                    videoStream = successInstance[0];
                }
                if (videoUrl.startsWith(Environment.getExternalStorageDirectory().getPath())) {
                    updatePlaybackViaText(getString(R.string.cache));
                    proceedPlay(videoUrl);
                } else {
                    if (videoStream != null) updatePlaybackViaText(videoStream.getHost());
                    TextView progressView = (TextView) findViewById(R.id.video_progress);
                    if (config.isConvertVideos() && videoUrl.contains("&codec=")) {
                        if (progressView != null) {
                            progressView.setText(R.string.conv_long);
                            progressView.setVisibility(View.VISIBLE);
                        }
                        proceedPlay(videoUrl);
                    } else if (config.isConvertVideos()) {
                        if (progressView != null) {
                            progressView.setText(getString(R.string.dvauha_msg, getString(R.string.loading)));
                            progressView.setVisibility(View.VISIBLE);
                        }
                        new ConvertVideoTask().execute(videoUrl);
                    } else {
                        proceedPlay(videoUrl);
                    }
                }
            } else {
                stopAndResetVideo();
                restoreVideoUI();
                updatePlaybackViaText(videoStream != null ? videoStream.getHost() : api.getHost());

                if (targetInstance != null) {
                    Manager.getInstance().removeDeadInstance(targetInstance);
                    Toast.makeText(context, "Failed to connect to this instance.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, errorMessage != null ? errorMessage : "Failed to fetch video URL", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void proceedPlay(final String targetUrl) {
        if (config.isUseExternalPlayer()) {
            if (config.isStreamPlayback()) {
                findViewById(R.id.video_loading).setVisibility(View.GONE);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.setDataAndType(Uri.parse(targetUrl), "video/mp4");
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                new DownloadVideoTask().execute(targetUrl);
            }
        } else {
            stopAndResetVideo();
            applyOpenCoreLayoutFix();
            attachVideoListeners();

            if (config.isStreamPlayback() || targetUrl.startsWith(Environment.getExternalStorageDirectory().getPath())) {
                videoView.setVisibility(View.VISIBLE);
                videoView.setMediaController(new MediaController(context));
                videoView.requestFocus(0);
                videoView.setVideoURI(Uri.parse(targetUrl));
                setupVideoTimeout();
            } else {
                new DownloadVideoTask().execute(targetUrl);
            }
        }
    }

    private class DownloadVideoTask extends AsyncTask<String, Integer, File> {
        private TextView progressView;

        @Override
        protected void onPreExecute() {
            progressView = (TextView) findViewById(R.id.video_progress);
            progressView.setVisibility(View.VISIBLE);
        }

        @Override
        protected File doInBackground(String... params) {
            try {
                String downloadUrl = params[0];
                File videoFile = getCachedVideoFile(videoId);
                if (!videoFile.exists()) {
                    HttpClient.downloadToFile(downloadUrl, videoFile.getAbsolutePath(), new HttpClient.DownloadProgressListener() {
                        private long lastUpdateTime = 0;

                        @Override
                        public void onProgress(final long bytesDownloaded, final long totalBytes) {
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
                return videoFile;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressView.setText(getString(R.string.percent, values[0]));
        }

        @Override
        protected void onPostExecute(File videoFile) {
            if (progressView != null) progressView.setVisibility(View.GONE);
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
                    stopAndResetVideo();
                    applyOpenCoreLayoutFix();
                    attachVideoListeners();

                    videoView.setVisibility(View.VISIBLE);
                    videoView.setVideoURI(Uri.fromFile(videoFile));
                    videoView.setMediaController(new MediaController(context));
                    videoView.requestFocus(0);
                }
            } else {
                if (isUsingMetadataUrl) {
                    Manager.getInstance().removeDeadInstance(api);
                    isUsingMetadataUrl = false;
                } else if (videoStream != null) {
                    Manager.getInstance().removeDeadInstance(videoStream);
                }
                stopAndResetVideo();

                findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                thumbnail.setVisibility(View.INVISIBLE);
                play.setVisibility(View.GONE);
                Toast.makeText(context, "Download failed. Trying another instance...", Toast.LENGTH_SHORT).show();
                new ResolveStreamTask(null).execute(videoId);
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
            return resultUrl[0];
        }

        @Override
        protected void onProgressUpdate(String... values) {
            TextView progressView = (TextView) findViewById(R.id.video_progress);
            if (progressView != null)
                progressView.setText(getString(R.string.dvauha_msg, values[0]));
        }

        @Override
        protected void onPostExecute(String downloadUrl) {
            TextView progressView = (TextView) findViewById(R.id.video_progress);
            if (progressView != null) progressView.setVisibility(View.GONE);

            if (downloadUrl != null) {
                proceedPlay(downloadUrl);
            } else {
                stopAndResetVideo();
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
                return api.getComments(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Comment> result) {
            if (result != null) {
                comments.clear();
                comments.addAll(result);
                commentsAdapter.notifyDataSetChanged();
            }
            commentsLoading.setVisibility(View.GONE);
            commentsLoaded = true;
        }
    }

    private class LoadRelatedTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected List<VideoInfo> doInBackground(String... params) {
            try {
                return api.getRelated(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<VideoInfo> result) {
            if (result != null) {
                relatedVideos.clear();
                relatedVideos.addAll(result);
                relatedAdapter.notifyDataSetChanged();
            }
            relatedLoading.setVisibility(View.GONE);
            relatedLoaded = true;
        }
    }
}