package ir.layercloud.cortexdrone.service.world;

/**
 * Tunable budgets for read-only world discovery.
 *
 * <p>These are policy knobs, not per-call arguments: they bound how much server
 * thread time a discovery pass may consume no matter which tool asked for it.
 * Keeping them here means the tool schema, the scanner and the tests all agree
 * on one number instead of each declaring its own copy.</p>
 */
public final class WorldScanBudget {
    private WorldScanBudget() {
    }

    /**
     * Wall-clock budget for one distant-resource spiral search.
     *
     * <p>The {@code find_distant} tool declares a 10 s timeout. The internal
     * deadline must leave room to serialize the result and return, so it is
     * slightly below the tool timeout. A single ring step reads
     * {@code 11 x 11 x column} blocks, which alone costs tens of milliseconds;
     * anything close to that aborts before the first ring completes.</p>
     */
    public static final long SEARCH_BUDGET_MS = 8_000L;

    /** Nearest spiral radius, in blocks. */
    public static final int SEARCH_MIN_RADIUS = 16;

    /** How far each spiral pass advances, in blocks. */
    public static final int SEARCH_RADIUS_STEP = 24;

    /** Blocks sampled around each spiral point along X and Z. */
    public static final int SEARCH_SAMPLE_RADIUS = 5;

    /** Extra chunks a distant search will force-load per call. */
    public static final int SEARCH_MAX_CHUNK_LOADS = 16;
}
