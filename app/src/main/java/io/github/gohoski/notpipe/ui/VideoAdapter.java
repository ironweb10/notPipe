package io.github.gohoski.notpipe.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

import io.github.gohoski.notpipe.R;
import io.github.gohoski.notpipe.VideoActivity;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Adapter for video lists with lazy loading support.
 * Only loads images for items that have been explicitly marked as visible.
 */
public class VideoAdapter extends ArrayAdapter<VideoInfo> {
    private Context context;

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects) {
        super(context, resource, objects);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View row = convertView;

        // Check if this adapter is being used inside our custom layout (VideoActivity)
        // or a standard native ListView (MainActivity).
        boolean isLazyLoading = parent instanceof AdapterLinearLayout;

        if (row == null) {
            LayoutInflater inflater = ((Activity) context).getLayoutInflater();
            row = inflater.inflate(R.layout.video_item, parent, false);
            holder = new ViewHolder();
            holder.videoTitle = (TextView) row.findViewById(R.id.video_title);
            holder.channelTitle = (TextView) row.findViewById(R.id.channel_title);
            holder.videoThumb = (ImageView) row.findViewById(R.id.video_thumbnail);
            holder.channelThumb = (ImageView) row.findViewById(R.id.channel_thumbnail);
            holder.duration = (TextView) row.findViewById(R.id.duration);
            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }

        final VideoInfo video = getItem(position);
        holder.videoTitle.setText(video.title);
        holder.channelTitle.setText(video.channel);

        if (video.duration != null && video.duration.length() > 0) {
            holder.duration.setText(video.duration);
            holder.duration.setVisibility(View.VISIBLE);
        } else {
            holder.duration.setVisibility(View.GONE);
        }

        holder.video = video;
        holder.imagesLoaded = false;
        holder.videoThumb.setImageBitmap(null);
        holder.channelThumb.setImageBitmap(null);

        // If we are in MainActivity (standard ListView), load images immediately!
        // Standard ListViews recycle naturally, so OOM is not an issue here.
        if (!isLazyLoading) {
            ImageLoader.loadImage(video.thumbnail, holder.videoThumb);
            ImageLoader.loadImage(video.channelThumbnail, holder.channelThumb);
            holder.imagesLoaded = true;
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(context, VideoActivity.class);
                intent.putExtra("ID", video.id);
                context.startActivity(intent);
            }
        });

        return row;
    }

    /**
     * Called by VideoActivity's ScrollView listener when this row becomes visible.
     */
    public void loadImagesForView(View row) {
        if (row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (!holder.imagesLoaded && holder.video != null) {
                ImageLoader.loadImage(holder.video.thumbnail, holder.videoThumb);
                ImageLoader.loadImage(holder.video.channelThumbnail, holder.channelThumb);
                holder.imagesLoaded = true;
            }
        }
    }

    private static class ViewHolder {
        TextView videoTitle;
        TextView channelTitle;
        TextView duration;
        ImageView videoThumb;
        ImageView channelThumb;
        VideoInfo video;
        boolean imagesLoaded;
    }
}