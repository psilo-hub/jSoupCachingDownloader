package free.svoss.tools.jSoupCachingDownloader;

import java.io.IOException;

/**
 * Thrown when the resource to download is larger than
 * {@link DownloaderConfig#getMaxFileSizeBytes()}.
 */
public class TooLargeResourceException extends IOException {

    private final String url;
    private final long actualSizeBytes;
    private final long maxSizeBytes;

    public TooLargeResourceException(String url, long actualSizeBytes, long maxSizeBytes) {
        super(String.format(
                "Resource %s is too large: %d bytes exceeds the configured maximum of %d bytes",
                url, actualSizeBytes, maxSizeBytes));
        this.url = url;
        this.actualSizeBytes = actualSizeBytes;
        this.maxSizeBytes = maxSizeBytes;
    }

    /** The url of the rejected resource. */
    public String getUrl() {
        return url;
    }

    /** The size of the resource in bytes. */
    public long getActualSizeBytes() {
        return actualSizeBytes;
    }

    /** The configured maximum size in bytes. */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }
}
