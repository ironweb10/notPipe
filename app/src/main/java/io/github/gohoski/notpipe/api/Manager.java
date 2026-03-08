package io.github.gohoski.notpipe.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.gohoski.notpipe.NotPipe;
import io.github.gohoski.notpipe.R;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;

/**
 * Manager class for handling multiple API instances with fallback capability.
 */
public class Manager {
    private static Manager instance;
    private Random random;
    private ConfigManager configManager;

    private List<Metadata> metadataInstances;
    private List<Trending> trendingInstances;
    private List<VideoStream> videoInstances; //360p only
    private List<VideoStream> hqInstances; //high-quality instances (480p+)

    private Manager() {
        random = new Random();
    }

    public static void init() {
        if (instance == null) {
            instance = new Manager();
            instance.initializeInstances();
        }
    }

    public static Manager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Manager.init() must be called first!");
        }
        return instance;
    }

    private void initializeInstances() {
        if (metadataInstances == null) {
            metadataInstances = new ArrayList<Metadata>();
            videoInstances = new ArrayList<VideoStream>();
            trendingInstances = new ArrayList<Trending>();
            hqInstances = new ArrayList<VideoStream>();
        } else {
            metadataInstances.clear();
            videoInstances.clear();
            trendingInstances.clear();
            hqInstances.clear();
        }

        if (configManager == null) {
            configManager = ConfigManager.getInstance();
        }

        Config config = configManager.getConfig();

        List<String> ytApiInstances = config.getYtApiLegacyInstances();
        for (int i = 0; i < ytApiInstances.size(); i++) {
            YtApiLegacy ytApi = new YtApiLegacy(ytApiInstances.get(i));
            metadataInstances.add(ytApi);
            trendingInstances.add(ytApi);
            videoInstances.add(ytApi);
            hqInstances.add(ytApi);
        }

        List<String> invInstances = config.getInvidiousInstances();
        for (int i = 0; i < invInstances.size(); i++) {
            Invidious inv = new Invidious(invInstances.get(i));
            metadataInstances.add(inv);
            videoInstances.add(inv);
        }

        List<String> yt2009List = config.getYt2009Instances();
        for (int i = 0; i < yt2009List.size(); i++) {
            Yt2009 yt2009 = new Yt2009(yt2009List.get(i));
            videoInstances.add(yt2009);
            hqInstances.add(yt2009);
        }

        videoInstances.add(new S60Tube());
    }

    public Metadata getMetadata() {
        if (metadataInstances.isEmpty()) throw new IllegalStateException("No Metadata instances");
        return createStatefulFallbackProxy(Metadata.class, metadataInstances);
    }

    public Trending getTrending() {
        if (trendingInstances.isEmpty()) throw new IllegalStateException("No Trending instances");
        return createStatefulFallbackProxy(Trending.class, trendingInstances);
    }

    public VideoStream getVideoStream() {
        if (videoInstances.isEmpty()) throw new IllegalStateException("No VideoStream instances");
        return createStatefulFallbackProxy(VideoStream.class, videoInstances);
    }

    public String getVideoUrl(String videoId, String quality) throws IOException {
        return getVideoUrl(videoId, quality, null, null);
    }

    public String getVideoUrl(String videoId, String quality, VideoStream preferredInstance, VideoStream[] successfulInstance) throws IOException {
        List<VideoStream> targetList = "360".equals(quality) ? new ArrayList<VideoStream>(videoInstances) : new ArrayList<VideoStream>(hqInstances);
        if (targetList.isEmpty()) throw new IllegalStateException("No video instances available.");

        // Prioritize the preferred instance if provided, shuffle the rest
        if (preferredInstance != null && targetList.contains(preferredInstance)) {
            targetList.remove(preferredInstance);
            targetList.add(0, preferredInstance);
            if (targetList.size() > 1) {
                List<VideoStream> rest = targetList.subList(1, targetList.size());
                java.util.Collections.shuffle(rest, random);
            }
        } else {
            java.util.Collections.shuffle(targetList, random);
        }

        Throwable lastError = null;

        for (int i = 0; i < targetList.size(); i++) {
            VideoStream currentInstance = targetList.get(i);
            try {
                String url = currentInstance.getVideoUrl(videoId, quality);
                if (successfulInstance != null && successfulInstance.length > 0) {
                    successfulInstance[0] = currentInstance;
                }
                return url;
            } catch (Exception e) {
                if (isDeadInstanceError(e)) {
                    removeDeadInstance(currentInstance);
                    lastError = e;
                } else {
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException(e.getMessage());
                }
            }
        }

        if (lastError != null) {
            if (videoInstances.isEmpty() || hqInstances.isEmpty()) {
                reloadInstances();
                showConnectionErrorToast();
            }
            if (lastError instanceof IOException) throw (IOException) lastError;
            throw new IOException(lastError.getMessage());
        }
        throw new IOException("All instances failed");
    }

    public String getVideoUrl(String videoId, String quality, int codec) throws IOException {
        return getVideoUrl(videoId, quality, codec, null, null);
    }

    public String getVideoUrl(String videoId, String quality, int codec, VideoStream preferredInstance, VideoStream[] successfulInstance) throws IOException {
        List<VideoStream> targetList = "360".equals(quality) ? new ArrayList<VideoStream>(videoInstances) : new ArrayList<VideoStream>(hqInstances);
        if (targetList.isEmpty()) throw new IllegalStateException("No video instances available.");

        if (configManager.getConfig().isConvertVideos()) {
            List<YtApiLegacy> ytApiLegacyInstances = new ArrayList<YtApiLegacy>();
            List<VideoStream> otherInstances = new ArrayList<VideoStream>();
            for (int i = 0; i < targetList.size(); i++) {
                VideoStream instance = targetList.get(i);
                if (instance instanceof YtApiLegacy) {
                    ytApiLegacyInstances.add((YtApiLegacy) instance);
                } else {
                    otherInstances.add(instance);
                }
            }

            // Try YtAPILegacy instances first for conversion
            if (preferredInstance instanceof YtApiLegacy && ytApiLegacyInstances.contains(preferredInstance)) {
                ytApiLegacyInstances.remove(preferredInstance);
                ytApiLegacyInstances.add(0, (YtApiLegacy) preferredInstance);
                if (ytApiLegacyInstances.size() > 1) {
                    List<YtApiLegacy> rest = ytApiLegacyInstances.subList(1, ytApiLegacyInstances.size());
                    java.util.Collections.shuffle(rest, random);
                }
            } else {
                java.util.Collections.shuffle(ytApiLegacyInstances, random);
            }

            if (!ytApiLegacyInstances.isEmpty()) {
                for (int i = 0; i < ytApiLegacyInstances.size(); i++) {
                    YtApiLegacy currentInstance = ytApiLegacyInstances.get(i);
                    try {
                        String url = currentInstance.getConvUrl(videoId, codec);
                        if (successfulInstance != null && successfulInstance.length > 0) {
                            successfulInstance[0] = currentInstance;
                        }
                        return url;
                    } catch (Exception e) {
                        if (isDeadInstanceError(e)) {
                            removeDeadInstance(currentInstance);
                        } else {
                            if (e instanceof IOException) throw (IOException) e;
                            throw new IOException(e.getMessage());
                        }
                    }
                }
            }

            // No YtAPILegacy instances left, use other instances
            targetList = otherInstances;
        }

        if (preferredInstance != null && targetList.contains(preferredInstance)) {
            targetList.remove(preferredInstance);
            targetList.add(0, preferredInstance);
            if (targetList.size() > 1) {
                List<VideoStream> rest = targetList.subList(1, targetList.size());
                java.util.Collections.shuffle(rest, random);
            }
        } else {
            java.util.Collections.shuffle(targetList, random);
        }

        Throwable lastError = null;
        for (int i = 0; i < targetList.size(); i++) {
            VideoStream currentInstance = targetList.get(i);
            try {
                String url = currentInstance.getVideoUrl(videoId, quality);
                if (successfulInstance != null && successfulInstance.length > 0) {
                    successfulInstance[0] = currentInstance;
                }
                return url;
            } catch (Exception e) {
                if (isDeadInstanceError(e)) {
                    removeDeadInstance(currentInstance);
                    lastError = e;
                } else {
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException(e.getMessage());
                }
            }
        }
        if (lastError != null) {
            if (videoInstances.isEmpty() || hqInstances.isEmpty()) {
                reloadInstances();
                showConnectionErrorToast();
            }
            if (lastError instanceof IOException) throw (IOException) lastError;
            throw new IOException(lastError.getMessage());
        }
        throw new IOException(lastError != null ? lastError.getMessage() : "All instances failed, check your Internet connection!");
    }

    /**
     Creates a dynamic proxy that retains one instance for the lifespan of the Activity.
     */
    @SuppressWarnings("unchecked")
    private <T> T createStatefulFallbackProxy(final Class<T> interfaceClass, final List<T> pool) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{ interfaceClass },
                new InvocationHandler() {
                    private T currentInstance = null;
                    private void pickNewInstance() {
                        if (!pool.isEmpty()) {
                            currentInstance = pool.get(random.nextInt(pool.size()));
                        } else {
                            currentInstance = null;
                        }
                    }

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (currentInstance == null) pickNewInstance();

                        Throwable lastError = null;

                        while (currentInstance != null) {
                            try {
                                return method.invoke(currentInstance, args);
                            } catch (InvocationTargetException e) {
                                lastError = e.getCause();

                                if (isDeadInstanceError(lastError)) {
                                    removeDeadInstance(currentInstance);
                                    pickNewInstance();
                                } else {
                                    throw lastError;
                                }
                            }
                        }

                        if (pool.isEmpty()) {
                            reloadInstances();
                            showConnectionErrorToast();
                        }

                        if (lastError != null) throw lastError;
                        throw new IOException("No available instances left for " + interfaceClass.getSimpleName());
                    }
                }
        );
    }

    public void removeDeadInstance(Object instance) {
        final String host;
        try {
            host = (String) instance.getClass().getMethod("getHost").invoke(instance);
            notifyDeadInstance(host);
        } catch (Exception ignored) {
            return;
        }
        removeByHost(metadataInstances, host);
        removeByHost(trendingInstances, host);
        removeByHost(videoInstances, host);
        removeByHost(hqInstances, host);
    }

    private void removeByHost(List list, String host) {
        java.util.Iterator iterator = list.iterator();
        while (iterator.hasNext()) {
            try {
                Object item = iterator.next();
                String itemHost = (String) item.getClass().getMethod("getHost").invoke(item);
                if (host.equals(itemHost)) {
                    iterator.remove();
                }
            } catch (Exception ignored) {}
        }
    }

    private void notifyDeadInstance(final String name) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = NotPipe.getAppContext();
                    if (context != null) {
                        Toast.makeText(context,
                                context.getString(R.string.dead_instance, name),
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void showConnectionErrorToast() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(NotPipe.getAppContext(),
                            "check your internet connection",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    private boolean isDeadInstanceError(Throwable t) {
        if (t == null) return false;

        if (t instanceof SocketTimeoutException ||
                t instanceof ConnectException ||
                t instanceof UnknownHostException ||
                t instanceof SocketException) {
            return true;
        }

        if (t instanceof IOException && !(t instanceof java.io.FileNotFoundException)) {
            return true;
        }

        return false;
    }

    public void reloadInstances() {
        initializeInstances();
    }

    public List<VideoStream> getVideoInstances() {
        return videoInstances;
    }

    public List<VideoStream> getHqInstances() {
        return hqInstances;
    }

    public static class InstanceInfo {
        public VideoStream instance;
        public String host;
        public String name;
        public boolean supportsAllQualities;
    }

    public List<InstanceInfo> videoInstancesInfo() {
        initializeInstances();
        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        List<VideoStream> allKnown = new ArrayList<VideoStream>();
        List<String> processed = new ArrayList<String>();
        allKnown.addAll(videoInstances);
        allKnown.addAll(hqInstances);

        for (int i = 0; i < allKnown.size(); i++) {
            VideoStream instance = allKnown.get(i);
            InstanceInfo info = new InstanceInfo();
            info.instance = instance;
            info.host = instance.getHost();
            info.name = instance.getName();
            info.supportsAllQualities = !(instance instanceof Invidious || instance instanceof S60Tube);
            if (!processed.contains(info.host)) {
                result.add(info);
                processed.add(info.host);
            }
        }

        return result;
    }
}