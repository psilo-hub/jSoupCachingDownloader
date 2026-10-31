package free.svoss.tools.jSoupCachingDownloader;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.nodes.Document;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Runs all tests against a local in-memory HTTP server (com.sun.net.httpserver),
 * so no internet access is needed. Uses a fresh, isolated disk cache per test.
 */
public class CachingDownloaderTest {

    private static final byte[] IMAGE_BYTES = {0x01, 0x02, 0x03, (byte) 0xFF, 0x42, 0x00, 0x7F};
    private static final String PAGE_HTML =
            "<html><head><title>test page</title></head><body><h1>hello</h1></body></html>";

    private static HttpServer server;
    private static String baseUrl;

    private static final java.util.concurrent.atomic.AtomicInteger pageHits = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger imageHits = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/page", exchange -> {
            pageHits.incrementAndGet();
            writeResponse(exchange, "text/html", PAGE_HTML.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/image", exchange -> {
            imageHits.incrementAndGet();
            writeResponse(exchange, "image/png", IMAGE_BYTES);
        });
        server.createContext("/big", exchange -> {
            byte[] body = new byte[100];
            writeResponse(exchange, "application/octet-stream", body);
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange exchange, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterClass
    public static void stopServer() {
        server.stop(0);
    }

    private Path cacheDir;

    @Before
    public void setUp() throws IOException {
        pageHits.set(0);
        imageHits.set(0);
        cacheDir = Files.createTempDirectory("jSoupCachingDownloaderTest");
        DownloaderConfig.setCacheDirectory(cacheDir);
        DownloaderConfig.setPageCacheMillis(DownloaderConfig.DEFAULT_PAGE_CACHE_MILLIS);
        DownloaderConfig.setMaxFileSizeBytes(DownloaderConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        DownloaderConfig.setUserAgent(DownloaderConfig.DEFAULT_USER_AGENT);
        DownloaderConfig.setTimeoutMillis(DownloaderConfig.DEFAULT_TIMEOUT_MILLIS);
    }

    @After
    public void tearDown() throws IOException {
        DownloaderConfig.resetToDefaults();
        clearTempCache();
    }

    private void clearTempCache() {
        clearTempCache(cacheDir);
        clearTempCache(DownloaderConfig.getCacheDirectory());
    }

    private static void clearTempCache(Path dir) {
        try {
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String pageUrl() {
        return baseUrl + "/page";
    }

    private String imageUrl() {
        return baseUrl + "/image";
    }

    @Test
    public void downloadPageReturnsDocument() throws IOException {
        Document doc = CachingDownloader.downloadPage(pageUrl());
        assertEquals("test page", doc.title());
        assertEquals(1, pageHits.get());
    }

    @Test
    public void freshPageIsServedFromCache() throws IOException {
        CachingDownloader.downloadPage(pageUrl());
        Document second = CachingDownloader.downloadPage(pageUrl());
        assertEquals("test page", second.title());
        assertEquals("second call must not hit the server", 1, pageHits.get());
    }

    @Test
    public void stalePageIsRefetched() throws IOException, InterruptedException {
        CachingDownloader.downloadPage(pageUrl());
        agePageCacheFileByMoreThanOneHour(pageUrl());
        CachingDownloader.downloadPage(pageUrl());
        assertEquals("aged cache entry must be refetched", 2, pageHits.get());
    }

    @Test
    public void forceRedownloadRefetchesPage() throws IOException {
        CachingDownloader.downloadPage(pageUrl());
        CachingDownloader.downloadPage(pageUrl(), true);
        assertEquals("forceRedownload must hit the server", 2, pageHits.get());
        CachingDownloader.downloadPage(pageUrl());
        assertEquals("the fresh copy must now be cached again", 2, pageHits.get());
    }

    @Test
    public void imageIsCachedForever() throws IOException {
        byte[] first = CachingDownloader.downloadImage(imageUrl());
        byte[] second = CachingDownloader.downloadImage(imageUrl());
        assertArrayEquals(IMAGE_BYTES, first);
        assertArrayEquals(IMAGE_BYTES, second);
        assertEquals("binary resources are never refetched", 1, imageHits.get());
    }

    @Test
    public void imageIsRefetchedWithForceRedownload() throws IOException {
        CachingDownloader.downloadImage(imageUrl());
        CachingDownloader.downloadImage(imageUrl(), true);
        assertEquals(2, imageHits.get());
    }

    @Test
    public void imageOnlyRefetchedWhenForced_evenWhenAged() throws IOException, InterruptedException {
        CachingDownloader.downloadImage(imageUrl());
        ageBinaryCacheFileByMoreThanOneHour(imageUrl());
        CachingDownloader.downloadImage(imageUrl());
        assertEquals("age must not expire binary cache entries", 1, imageHits.get());
        CachingDownloader.downloadImage(imageUrl(), true);
        assertEquals(2, imageHits.get());
    }

    @Test
    public void pageSizeLimitIsEnforced() throws Exception {
        DownloaderConfig.setMaxFileSizeBytes(50);
        assertThrows(TooLargeResourceException.class, () -> CachingDownloader.downloadImage(baseUrl + "/big"));
        assertThrows(TooLargeResourceException.class, () -> CachingDownloader.downloadPage(baseUrl + "/big"));
        // nothing oversize must end up in the cache
        assertTrue(countFilesIn(cacheDir.resolve("files")) == 0);
        assertTrue(countFilesIn(cacheDir.resolve("pages")) == 0);
    }

    @Test
    public void clearCacheForcesRefetch() throws IOException {
        CachingDownloader.downloadPage(pageUrl());
        CachingDownloader.downloadImage(imageUrl());
        CachingDownloader.clearCache();
        CachingDownloader.downloadPage(pageUrl());
        CachingDownloader.downloadImage(imageUrl());
        assertEquals(2, pageHits.get());
        assertEquals(2, imageHits.get());
    }

    @Test
    public void clearExpiredPagesRemovesOnlyOldPages() throws IOException, InterruptedException {
        CachingDownloader.downloadPage(pageUrl());
        CachingDownloader.downloadImage(imageUrl());
        agePageCacheFileByMoreThanOneHour(pageUrl());
        DownloaderConfig.setPageCacheMillis(60000); // 1 minute
        CachingDownloader.clearExpiredPages();
        assertEquals(0, countFilesIn(cacheDir.resolve("pages")));
        assertEquals("binary cache is never expired", 1, countFilesIn(cacheDir.resolve("files")));
        CachingDownloader.downloadPage(pageUrl());
        assertEquals("expired page must be refetched", 2, pageHits.get());
    }

    @Test
    public void rejectsNonHttpUrls() {
        assertThrows(IllegalArgumentException.class, () -> CachingDownloader.downloadPage("file:///etc/hosts"));
        assertThrows(IllegalArgumentException.class, () -> CachingDownloader.downloadImage("ftp://example.com/a.png"));
        assertThrows(IllegalArgumentException.class, () -> CachingDownloader.downloadPage(null));
    }

    private void agePageCacheFileByMoreThanOneHour(String url) throws IOException {
        Path page = DiskCache.pageFile(DiskCache.cacheKey(url));
        assertTrue(Files.isRegularFile(page));
        // 9h > the 8h default max age
        Files.setLastModifiedTime(page, FileTime.from(Instant.now().minusMillis(9 * 60 * 60 * 1000)));
    }

    private void ageBinaryCacheFileByMoreThanOneHour(String url) throws IOException {
        Path file = DiskCache.binaryFile(DiskCache.cacheKey(url));
        assertTrue(Files.isRegularFile(file));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusMillis(9 * 60 * 60 * 1000)));
    }

    private int countFilesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(Files::isRegularFile).count();
        }
    }
}
