package io.github.gohoski.notpipe.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Gleb on 29.01.2026
 * Utility class for loading images from URLs with optional memory caching
 */
public class ImageLoader {
    private static final Hashtable<String, Bitmap> memoryCache = new Hashtable<String, Bitmap>();
    private static final LinkedList<String> cacheHistory = new LinkedList<String>();
    private static final Hashtable<String, List<ImageView>> pendingViews = new Hashtable<String, List<ImageView>>();
    private static final int MAX_CACHE_SIZE = 30;
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    /**
     * Load an image from URL into an ImageView with caching enabled.
     * Best for ListViews where images are recycled.
     */
    public static void loadImage(final String url, final ImageView imageView) {
        loadImage(url, imageView, true);
    }
    
    /**
     * Load an image from URL into an ImageView.
     * @param url The image URL
     * @param imageView The ImageView to display the image in
     * @param useCache Whether to use memory caching
     */
    public static synchronized void loadImage(final String url, final ImageView imageView, final boolean useCache) {
        if (imageView != null) {
            imageView.setTag(url);
        }
        if (useCache && memoryCache.containsKey(url)) {
            Bitmap cachedBitmap = memoryCache.get(url);
            if (imageView != null && cachedBitmap != null && !cachedBitmap.isRecycled()) {
                imageView.setImageBitmap(cachedBitmap);
            }
            return;
        }
        if (pendingViews.containsKey(url)) {
            if (imageView != null) {
                pendingViews.get(url).add(imageView);
            }
            return;
        }
        final List<ImageView> views = new ArrayList<ImageView>();
        if (imageView != null) {
            views.add(imageView);
        }
        pendingViews.put(url, views);
        executor.execute(new ImageLoadRunnable(url, useCache));
    }

    private static class ImageLoadRunnable implements Runnable {
        private final String url;
        private final boolean useCache;

        ImageLoadRunnable(String url, boolean useCache) {
            this.url = url;
            this.useCache = useCache;
        }

        @Override
        public void run() {
            final int MAX_RETRIES = 2;
            Bitmap bmp = null;

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    URL aURL = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) aURL.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    break;
                } catch (java.net.SocketTimeoutException e) {
                    Log.w("ImageLoader", "Timeout on attempt " + (attempt + 1) + " for " + url);
                    if (attempt == MAX_RETRIES) {
                        Log.e("ImageLoader", "Final attempt failed. Giving up.");
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                } catch (OutOfMemoryError e) {
                    Log.e("ImageLoader", "OutOfMemoryError loading " + url + ", attempting to free memory");
                    System.gc();
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    if (attempt == MAX_RETRIES) break;
                } catch (Exception e) {
                    Log.e("ImageLoader", "Download Error: " + e.getMessage());
                    break;
                }
            }
            final Bitmap finalBmp = bmp;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    List<ImageView> pendingList;
                    synchronized (ImageLoader.class) {
                        pendingList = new ArrayList<ImageView>(pendingViews.get(url));
                        pendingViews.remove(url);
                    }

                    if (finalBmp != null) {
                        if (useCache) {
                            if (cacheHistory.contains(url)) {
                                cacheHistory.remove(url);
                            }
                            cacheHistory.addLast(url);
                            memoryCache.put(url, finalBmp);

                            if (cacheHistory.size() > MAX_CACHE_SIZE) {
                                String oldest = cacheHistory.removeFirst();
                                memoryCache.remove(oldest);
                            }
                        }

                        for (ImageView iv : pendingList) {
                            if (iv != null) {
                                String currentTag = (String) iv.getTag();
                                if (url.equals(currentTag)) {
                                    iv.setImageBitmap(finalBmp);
                                }
                            }
                        }
                    }
                }
            });
        }
    }
    
    /**
     * Preload an image into cache without displaying it.
     */
    public static void preloadImage(final String url) {
        loadImage(url, null, true);
    }
    
    /**
     * Cancel loading for a specific ImageView.
     * Call this when the view scrolls off-screen.
     */
    public static void cancelLoad(ImageView imageView) {
        if (imageView != null) {
            imageView.setTag(null);
        }
    }
    
    /**
     * Cancel all pending image loads.
     * Useful when clearing a list.
     */
    public static void cancelAllLoads() {
        synchronized (ImageLoader.class) {
            pendingViews.clear();
        }
    }
    
    /**
     * Clear the memory cache to free memory.
     */
    public static void clearCache() {
        memoryCache.clear();
        cacheHistory.clear();
        System.gc();
    }
    
    /**
     * Get current cache size.
     */
    public static int getCacheSize() {
        return memoryCache.size();
    }
}