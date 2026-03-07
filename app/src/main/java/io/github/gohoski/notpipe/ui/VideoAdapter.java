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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private Set<Integer> loadedPositions = new HashSet<Integer>();
    private static final int INITIAL_LOAD_COUNT = 0;

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects) {
        super(context, resource, objects);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View row = convertView;
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
        if (video.duration != null) {
            holder.duration.setText(video.duration);
            holder.duration.setVisibility(View.VISIBLE);
        }

        holder.position = position;
        holder.video = video;
        holder.videoThumb.setImageBitmap(null);
        holder.channelThumb.setImageBitmap(null);

        if (!isLazyLoading || loadedPositions.contains(position)) {
            ImageLoader.loadImage(video.thumbnail, holder.videoThumb);
            ImageLoader.loadImage(video.channelThumbnail, holder.channelThumb);
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
     * Load images for a range of items.
     * Call this when items become visible during scrolling.
     */
    public void loadImagesForRange(int start, int end) {
        boolean changed = false;
        for (int i = start; i <= end && i < getCount(); i++) {
            if (!loadedPositions.contains(i)) {
                loadedPositions.add(i);
                changed = true;
            }
        }
        if (changed) {
            notifyDataSetChanged();
        }
    }

    /**
     * Load initial set of images.
     * Call this after setting the adapter.
     */
    public void loadInitialImages() {
        loadImagesForRange(0, INITIAL_LOAD_COUNT - 1);
    }

    /**
     * Clear all loaded images and reset.
     */
    public void reset() {
        loadedPositions.clear();
        notifyDataSetChanged();
    }

    private static class ViewHolder {
        TextView videoTitle;
        TextView channelTitle;
        TextView duration;
        ImageView videoThumb;
        ImageView channelThumb;
        VideoInfo video;
        int position;
    }
}
