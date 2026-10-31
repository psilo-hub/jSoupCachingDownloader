package free.svoss.tools.jSoupCachingDownloader;

import lombok.experimental.UtilityClass;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A caching wrapper around jsoup.
 *
 * <p>{@link #downloadPage(String)} returns a jsoup {@link Document} and caches
 * the raw HTML on disk. A cached page is considered valid for
 * {@link DownloaderConfig#getPageCacheMillis()} (default: 8 hours), judged by
 * the cache file's lastModified time; afterwards it is automatically refetched.</p>
 *
 * <p>{@link #downloadImage(String)} returns the raw bytes of the resource and
 * caches them on disk <em>forever</em>: a cached binary file is only fetched
 * from the internet again if {@code forceRedownload} is {@code true}.</p>
 *
 * <p>Sensible defaults (user agent, timeouts, maximum file size, cache
 * directory) are stored in {@link DownloaderConfig} and can be overwritten with
 * its setters.</p>
 */
@UtilityClass
public class CachingDownloader {

    /** Lock per cache key so concurrent calls for the same url don't race. */
    private static final ConcurrentHashMap<String, ReentrantLock> KEY_LOCKS = new ConcurrentHashMap<>();

    /**
     * Downloads the page, using the 8-hour disk cache.
     * @param url page url, only {@code http} and {@code https} are supported
     * @throws IOException if the download fails
     */
    public Document downloadPage(String url) throws IOException {
        return downloadPage(url, false);
    }

    /**
     * Downloads the page. When {@code forceRedownload} is {@code true}, a
     * freshly downloaded copy replaces the cached one, regardless of its age.
     * If that download fails, the stored copy is <em>not</em> used as fallback.
     */
    public Document downloadPage(String url, boolean forceRedownload) throws IOException {
        requireHttpUrl(url);
        String cacheKey = DiskCache.cacheKey(url);
        Path cacheFile = DiskCache.pageFile(cacheKey);
        ReentrantLock lock = lockFor(cacheKey);
        lock.lock();
        try {
            if (!forceRedownload && Files.isRegularFile(cacheFile)) {
                long ageMillis = DiskCache.ageMillis(cacheFile);
                if (ageMillis >= 0 && ageMillis <= DownloaderConfig.getPageCacheMillis()) {
                    return parsePage(cacheFile, url);
                }
            }
            Connection.Response response = fetch(url);
            byte[] body = checkedBody(response, url);
            DiskCache.writeAtomically(cacheFile, body);
            String charset = response.charset();
            if (charset != null) {
                DiskCache.writeAtomically(DiskCache.pageCharsetFile(cacheKey), charset.getBytes(StandardCharsets.ISO_8859_1));
            }
            return parse(body, url, charset);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Downloads the image (or any other binary file), using a disk cache that
     * never expires. A cached copy is returned on every call until the cache
     * is cleared.
     */
    public byte[] downloadImage(String url) throws IOException {
        return downloadImage(url, false);
    }

    /**
     * Downloads the image (or any other binary file). When
     * {@code forceRedownload} is {@code true}, the file is fetched from the
     * internet again and replaces the cached copy. If that download fails, the
     * stored copy is <em>not</em> used as fallback.
     */
    public byte[] downloadImage(String url, boolean forceRedownload) throws IOException {
        requireHttpUrl(url);
        String cacheKey = DiskCache.cacheKey(url);
        Path cacheFile = DiskCache.binaryFile(cacheKey);
        ReentrantLock lock = lockFor(cacheKey);
        lock.lock();
        try {
            if (!forceRedownload && Files.isRegularFile(cacheFile)) {
                return Files.readAllBytes(cacheFile);
            }
            Connection.Response response = fetch(url);
            byte[] body = checkedBody(response, url);
            DiskCache.writeAtomically(cacheFile, body);
            return body;
        } finally {
            lock.unlock();
        }
    }

    /** Deletes everything in the disk cache. */
    public void clearCache() throws IOException {
        DiskCache.clearAll();
    }

    /**
     * Deletes only cached pages that are older than
     * {@link DownloaderConfig#getPageCacheMillis()}. Binary files are cached
     * forever and are never expired by this method.
     */
    public void clearExpiredPages() throws IOException {
        DiskCache.clearExpiredPages();
    }

    private Connection.Response fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(DownloaderConfig.getUserAgent())
                .timeout(DownloaderConfig.getTimeoutMillis())
                .maxBodySize((int) Math.min(DownloaderConfig.getMaxFileSizeBytes(), Integer.MAX_VALUE))
                .followRedirects(true)
                .ignoreContentType(true)
                .execute();
    }

    private byte[] checkedBody(Connection.Response response, String url) throws IOException {
        long max = DownloaderConfig.getMaxFileSizeBytes();
        String contentLength = response.header("Content-Length");
        if (contentLength != null) {
            try {
                long size = Long.parseLong(contentLength.trim());
                if (size > max) {
                    throw new TooLargeResourceException(url, size, max);
                }
            } catch (NumberFormatException ignored) {
                // header not a plain number: fall through to the actual size check below
            }
        }
        byte[] body = response.bodyAsBytes();
        if (body.length > max) {
            throw new TooLargeResourceException(url, body.length, max);
        }
        return body;
    }

    private Document parse(byte[] html, String url, String charset) throws IOException {
        return Jsoup.parse(new ByteArrayInputStream(html), charset, urlWithoutFragment(url));
    }

    private Document parsePage(Path cacheFile, String url) throws IOException {
        byte[] html = Files.readAllBytes(cacheFile);
        Path charsetFile = DiskCache.pageCharsetFile(DiskCache.cacheKey(url));
        String charset = null;
        if (Files.isRegularFile(charsetFile)) {
            charset = new String(Files.readAllBytes(charsetFile), StandardCharsets.ISO_8859_1).trim();
        }
        return parse(html, url, charset);
    }

    private String urlWithoutFragment(String url) {
        int hashIndex = url.indexOf('#');
        return hashIndex >= 0 ? url.substring(0, hashIndex) : url;
    }

    private ReentrantLock lockFor(String cacheKey) {
        return KEY_LOCKS.computeIfAbsent(cacheKey, key -> new ReentrantLock());
    }

    private static void requireHttpUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be null or empty");
        }
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("Only http and https urls are supported, got: " + url);
        }
    }
}
