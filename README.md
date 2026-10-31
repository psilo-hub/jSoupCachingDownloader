# jSoupCachingDownloader

A small Java library that wraps [jsoup](https://jsoup.org/) to download web pages and
binary files (images etc.) with a transparent on-disk cache.

Runs on Java 8 or later (bytecode level 8, dependencies: jsoup 1.17.2, Lombok at
compile time only).

## Usage

```java
// Download a page, returns a jsoup Document
Document doc = CachingDownloader.downloadPage("https://example.com/articles/1");

// Download an image (or any other binary file), returns raw bytes
byte[] png = CachingDownloader.downloadImage("https://example.com/pic.png");

// Refetch even if a cached copy exists
byte[] fresh = CachingDownloader.downloadImage("https://example.com/pic.png", true);
```

## Caching behavior

| | Pages (`downloadPage`) | Binary files (`downloadImage`) |
|---|---|---|
| Max age | **8 hours**, based on the cache file's `lastModified` time | **forever** |
| Refetch trigger | age > max age, or `forceRedownload = true` | only `forceRedownload = true` |
| Stored content | raw HTML (parsed to `Document` on every call) | raw bytes |

Cache files are keyed by a SHA-256 hash of the URL (URL fragment ignored).
Writes are atomic (temp file + move), and the page cache also stores the received
charset so pages are always parsed correctly. Concurrent calls for the same URL
are serialized with per-key locks.

If a forced redownload fails, the failure is thrown instead of falling back to
the cached copy.

## Configuration

All defaults live in `free.svoss.tools.jSoupCachingDownloader.DownloaderConfig`
as static fields and can be overwritten at runtime:

| Setter | Default |
|---|---|
| `setUserAgent(String)` | `Mozilla/5.0 (compatible; jSoupCachingDownloader/1.0; ...)` |
| `setTimeoutMillis(int)` | `10 000` ms (connect/read timeout) |
| `setMaxFileSizeBytes(long)` | `10 MiB` (larger responses throw `TooLargeResourceException`) |
| `setPageCacheMillis(long)` | `8` hours |
| `setCacheDirectory(Path)` | `{java.io.tmpdir}/jSoupCachingDownloader` |
| `resetToDefaults()` | restores all of the above |

## Cache maintenance

```java
CachingDownloader.clearCache();         // wipe everything (pages + binary files)
CachingDownloader.clearExpiredPages();  // delete only pages older than the max age
```

## Building

```bash
mvn test     # runs the suite against a local in-memory HTTP server (no internet needed)
mvn package  # produces the library jar
```
