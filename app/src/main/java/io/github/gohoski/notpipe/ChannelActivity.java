package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Created by Gleb on 12.03.2026.
 */

public class ChannelActivity extends Activity {
    Metadata api;
    String channelId;
    Channel channel;
    LinearLayout channelLayout;
    ListView videoList;
    Context context;
    VideoAdapter adapter;
    ImageView banner;
    boolean isDestroyedFlag = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel);

        channelId = getIntent().getStringExtra("ID");
        channelLayout = (LinearLayout) findViewById(R.id.channel);
        videoList = (ListView) findViewById(R.id.videos);
        banner = (ImageView) findViewById(R.id.banner);
        context = this;

        videoList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ImageLoader.clearCache(); System.gc();
                Intent intent = new Intent(ChannelActivity.this, VideoActivity.class);
                intent.putExtra("ID", channel.videos.get(position).id);
                startActivity(intent);
            }
        });

        channelLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (channel == null) return;

                TextView text = new TextView(context);
                text.setText(getString(R.string.subscribers, NumberFormat.getNumberInstance().format(channel.subscriberCount))
                        + "\n\n" + channel.description + "\n\n" + getString(R.string.video_count, channel.videoCount));
                text.setPadding(15,15,15,15);

                ScrollView scroll = new ScrollView(context);
                scroll.addView(text);

                new AlertDialog.Builder(context).setTitle(channel.title).setView(scroll).show();
            }
        });

        new LoadChannelTask().execute(channelId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (channel != null && banner != null) {
            ImageLoader.loadImage(channel.banner, banner, true);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
        if (isFinishing()) {
            ImageLoader.clearCache();
        }
        if (banner != null) {
            banner.setImageDrawable(null);
        }
    }

    private class LoadChannelTask extends AsyncTask<String, Void, Channel> {
        @Override
        protected Channel doInBackground(String... strings) {
            try {
                if (isCancelled()) return null;
                api = Manager.getInstance().getMetadata();
                return api.getChannel(strings[0]);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Channel fetched) {
            if (isCancelled()) return;
            channel = fetched;
            findViewById(R.id.loading).setVisibility(View.GONE);
            channelLayout.setVisibility(View.VISIBLE);
            videoList.setVisibility(View.VISIBLE);

            ((TextView) findViewById(R.id.title)).setText(channel.title);
            ((TextView) findViewById(R.id.subscribers)).setText(getString(R.string.subscribers, Utils.formatNumber(context, channel.subscriberCount)));
            ((TextView) findViewById(R.id.description)).setText(channel.description);
            adapter = new VideoAdapter(context, R.layout.video_item_compact, channel.videos, true);
            videoList.setAdapter(adapter);
            ImageLoader.loadImage(channel.thumbnail, ((ImageView) findViewById(R.id.thumbnail)), false);
            ImageLoader.loadImage(channel.banner, ((ImageView) findViewById(R.id.banner)), false);
        }
    }
}
