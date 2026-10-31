package free.svoss.tools.jSoupCachingDownloader;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Package-private helper that manages the on-disk cache layout:
 * {@code <cacheDirectory>/pages/<sha256>}for HTML pages and
 * {@code <cacheDirectory>/files/<sha256>} for binary resources, plus a
 * {@code .charset} sidecar file per page storing the received charset.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class DiskCache {

    private static final String PAGES_DIR = "pages";
    private static final String FILES_DIR = "files";
    private static final String CHARSET_SUFFIX = ".charset";

    /** Directory containing cached HTML pages. */
    static Path pagesDir() {
        return DownloaderConfig.getCacheDirectory().resolve(PAGES_DIR);
    }

    /** Directory containing cached binary files (images etc.). */
    static Path filesDir() {
        return DownloaderConfig.getCacheDirectory().resolve(FILES_DIR);
    }

    /** Cache file holding the raw HTML of the page with the given cache key. */
    static Path pageFile(String cacheKey) {
        return pagesDir().resolve(cacheKey);
    }

    /** Sidecar file next to {@link #pageFile(String)} holding the charset name. */
    static Path pageCharsetFile(String cacheKey) {
        return pagesDir().resolve(cacheKey + CHARSET_SUFFIX);
    }

    /** Cache file holding the raw bytes of the binary resource with the given cache key. */
    static Path binaryFile(String cacheKey) {
        return filesDir().resolve(cacheKey);
    }

    /**
     * Computes the cache key for an url: SHA-256 of the url without its
     * fragment part, hex encoded. The fragment is stripped because
     * {@code http://x/page#a} and {@code http://x/page#b} are the same page.
     */
    @SneakyThrows(java.security.NoSuchAlgorithmException.class)
    static String cacheKey(String url) {
        String withoutFragment = url;
        int hashIndex = url.indexOf('#');
        if (hashIndex >= 0) {
            withoutFragment = url.substring(0, hashIndex);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(withoutFragment.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    /**
     * Writes {@code data} to {@code target} atomically (temp file + move) and
     * sets the file's lastModified time to now, since the page freshness check
     * relies on it.
     */
    static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveNotSupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
    }

    /** Age in milliseconds since the file's lastModified time; negative if the time is in the future. */
    static long ageMillis(Path file) throws IOException {
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        return System.currentTimeMillis() - lastModified;
    }

    /** Deletes every cached file (pages, sidecars and binary files). */
    static void clearAll() throws IOException {
        deleteContents(pagesDir());
        deleteContents(filesDir());
    }

    /**
     * Deletes cached pages older than {@code pageCacheMillis} together with
     * their {@code .charset} sidecar files. Binary files are never expired,
     * they are cached forever.
     */
    static void clearExpiredPages() throws IOException {
        List<Path> expired = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pagesDir())) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (file.getFileName().toString().endsWith(CHARSET_SUFFIX)) {
                    continue;
                }
                long age = ageMillis(file);
                if (age >= 0 && age > DownloaderConfig.getPageCacheMillis()) {
                    expired.add(file);
                    Path sidecar = file.resolveSibling(file.getFileName().toString() + CHARSET_SUFFIX);
                    if (Files.isRegularFile(sidecar)) {
                        expired.add(sidecar);
                    }
                }
            }
        }
        for (Path file : expired) {
            Files.deleteIfExists(file);
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.delete(file);
            }
        }
    }

    private static String toHex(byte... bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
