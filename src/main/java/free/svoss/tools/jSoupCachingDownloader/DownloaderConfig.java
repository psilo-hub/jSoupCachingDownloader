package free.svoss.tools.jSoupCachingDownloader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Holds the global (static) defaults used by {@link CachingDownloader}.
 * Every value can be overwritten at runtime through the generated setters;
 * {@link #resetToDefaults()} restores the original values.
 */
public final class DownloaderConfig {

    /** Default "User-Agent" header sent with every request. */
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (compatible; jSoupCachingDownloader/1.0; +https://github.com/jsoup/jsoup)";

    /** Default connect/read timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    /** Default maximum allowed response body size in bytes (10 MiB). */
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

    /** Default time a cached page stays valid: 8 hours, based on the cache file's lastModified time. */
    public static final long DEFAULT_PAGE_CACHE_MILLIS = TimeUnit.HOURS.toMillis(8);

    private static volatile String userAgent = DEFAULT_USER_AGENT;

    private static volatile int timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    private static volatile long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;

    private static volatile long pageCacheMillis = DEFAULT_PAGE_CACHE_MILLIS;

    private static volatile Path cacheDirectory = defaultCacheDirectory();

    private DownloaderConfig() {
    }

    /** The user agent currently sent with every request. */
    public static String getUserAgent() {
        return userAgent;
    }

    /** The current connect/read timeout in milliseconds. */
    public static int getTimeoutMillis() {
        return timeoutMillis;
    }

    /** The current maximum allowed response body size in bytes. */
    public static long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    /** The current maximum age of a cached page in milliseconds. */
    public static long getPageCacheMillis() {
        return pageCacheMillis;
    }

    /** The current cache directory. */
    public static Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * Overwrites the user agent sent with every request.
     * @param userAgent the User-Agent header value, must not be {@code null}
     */
    public static void setUserAgent(String userAgent) {
        if (userAgent == null || userAgent.trim().isEmpty()) {
            throw new IllegalArgumentException("userAgent must not be null or empty");
        }
        DownloaderConfig.userAgent = userAgent;
    }

    /**
     * Overwrites the connect/read timeout.
     * @param timeoutMillis timeout in milliseconds, must be greater than 0
     */
    public static void setTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        DownloaderConfig.timeoutMillis = timeoutMillis;
    }

    /**
     * Overwrites the maximum allowed response body size.
     * @param maxFileSizeBytes maximum size in bytes, must be greater than 0
     */
    public static void setMaxFileSizeBytes(long maxFileSizeBytes) {
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be > 0");
        }
        DownloaderConfig.maxFileSizeBytes = maxFileSizeBytes;
    }

    /**
     * Overwrites how long a cached page stays valid (based on lastModified).
     * @param pageCacheMillis maximum age of a cached page in milliseconds, must be >= 0
     */
    public static void setPageCacheMillis(long pageCacheMillis) {
        if (pageCacheMillis < 0) {
            throw new IllegalArgumentException("pageCacheMillis must be >= 0");
        }
        DownloaderConfig.pageCacheMillis = pageCacheMillis;
    }

    /**
     * Overwrites the directory used for the disk cache. It is created lazily on first write.
     * @param cacheDirectory the cache directory, must not be {@code null}
     */
    public static void setCacheDirectory(Path cacheDirectory) {
        if (cacheDirectory == null) {
            throw new IllegalArgumentException("cacheDirectory must not be null");
        }
        DownloaderConfig.cacheDirectory = cacheDirectory.toAbsolutePath();
    }

    /** Restores all defaults, including the default cache directory. */
    public static void resetToDefaults() {
        userAgent = DEFAULT_USER_AGENT;
        timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
        pageCacheMillis = DEFAULT_PAGE_CACHE_MILLIS;
        cacheDirectory = defaultCacheDirectory();
    }

    private static Path defaultCacheDirectory() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "jSoupCachingDownloader");
    }
}
