package io.github.gohoski.notpipe.ui;

import android.app.Activity;
import android.content.Context;
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
import io.github.gohoski.notpipe.Utils;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Adapter for comments with lazy loading support.
 * Only loads images for items that have been explicitly marked as visible.
 */
public class CommentAdapter extends ArrayAdapter<Comment> {
    private Context context;
    private Set<Integer> loadedPositions = new HashSet<Integer>();
    private static final int INITIAL_LOAD_COUNT = 8; // Load first 8 comments

    public CommentAdapter(Context context, int resource, List<Comment> objects) {
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
            row = inflater.inflate(R.layout.comment_item, parent, false);
            holder = new ViewHolder();
            holder.channelTitle = (TextView) row.findViewById(R.id.channel_title);
            holder.content = (TextView) row.findViewById(R.id.content);
            holder.date = (TextView) row.findViewById(R.id.date);
            holder.channelThumb = (ImageView) row.findViewById(R.id.channel_thumbnail);
            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }
        
        Comment comment = getItem(position);
        holder.channelTitle.setText(comment.channel);
        holder.content.setText(comment.content);
        holder.date.setText(Utils.formatTimeAgo(context, comment.publishedAt));
        
        // Store references for lazy loading
        holder.position = position;
        holder.comment = comment;
        
        // Always clear first to prevent showing recycled images
        holder.channelThumb.setImageBitmap(null);
        
        // For ListView: load immediately. For AdapterLinearLayout: only if marked visible
        if (!isLazyLoading || loadedPositions.contains(position)) {
            ImageLoader.loadImage(comment.channelThumbnail, holder.channelThumb);
        }
        
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
        TextView channelTitle;
        TextView content;
        TextView date;
        ImageView channelThumb;
        Comment comment;
        int position;
    }
}
