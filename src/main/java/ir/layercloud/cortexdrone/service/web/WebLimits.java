package ir.layercloud.cortexdrone.service.web;

/**
 * Shared web lookup bounds and network deadlines.
 *
 * <p>Regression: {@code MAX_FETCH_CHARS} was declared independently in the tool
 * schema and the lookup service, and the connect/read timeouts were inline in
 * three services.</p>
 */
public final class WebLimits {
    private WebLimits() {
    }

    /** Smallest number of search hits a caller may request. */
    public static final int MIN_RESULTS = 1;
    /** Default number of search hits. */
    public static final int DEFAULT_RESULTS = 5;
    /** Largest response body read from the network. */
    public static final int MAX_BYTES = 2_000_000;
    /** Initial cache capacity. */
    public static final int CACHE_SIZE = 32;
    /** Largest cache entry count. */
    public static final int CACHE_MAX_ENTRIES = 48;
    /** Cache time-to-live. */
    public static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    /** Largest number of search hits. */
    public static final int MAX_RESULTS = 10;
    /** Smallest text/character cap. */
    public static final int MIN_CHARS = 500;
    /** Largest fetched-text cap. */
    public static final int MAX_FETCH_CHARS = 20_000;
    /** Default fetched-text cap. */
    public static final int DEFAULT_FETCH_CHARS = 6_000;
    /** Largest wiki-extract cap. */
    public static final int MAX_WIKI_CHARS = 12_000;
    /** Largest number of matching snippets per file. */
    public static final int MAX_MATCHES_PER_FILE = 3;
    /** Default wiki-extract cap. */
    public static final int DEFAULT_WIKI_CHARS = 4_000;

    /** Connect timeout for documentation lookup. */
    public static final int DOC_CONNECT_TIMEOUT_MS = 2_500;
    /** Read timeout for documentation lookup. */
    public static final int DOC_READ_TIMEOUT_MS = 3_000;
    /** Connect timeout for web lookup. */
    public static final int WEB_CONNECT_TIMEOUT_MS = 6_000;
    /** Read timeout for web lookup. */
    public static final int WEB_READ_TIMEOUT_MS = 10_000;
}
