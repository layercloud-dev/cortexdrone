package ir.layercloud.cortexdrone.manager.chunk.impl;

/**
 * Per-drone bookkeeping that decides when the chunk plan must be recomputed.
 *
 * <p>Extracted from {@code ChunkLoadingManager}. Re-planning every tick would
 * rebuild the corridor for a stationary drone; re-planning only on movement
 * would let a rotating plan drift. The rule is: recompute when the drone
 * changes chunk, and otherwise every {@link #PLAN_INTERVAL_TICKS}.</p>
 */
final class DroneChunkCursor {
    /** Recompute the plan at least this often, in ticks. */
    static final int PLAN_INTERVAL_TICKS = 5;
    /** Minimum ticks between two identical ownership log lines. */
    static final int LOG_INTERVAL_TICKS = 600;

    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;
    private int dimension = Integer.MIN_VALUE;
    private long lastPlanTick = Long.MIN_VALUE;
    private long lastLogTick = Long.MIN_VALUE;
    private int lastManaged = -1;
    private int lastPlayerOwned = -1;

    /**
     * True when the plan should be recomputed.
     *
     * @param hasTicket whether a ticket already exists for this drone
     */
    boolean shouldReplan(int newChunkX, int newChunkZ, int newDimension, long now,
                         boolean hasTicket) {
        boolean moved = this.chunkX != newChunkX || this.chunkZ != newChunkZ
                || this.dimension != newDimension;
        boolean periodic = elapsed(now, this.lastPlanTick) >= PLAN_INTERVAL_TICKS;
        return moved || periodic || !hasTicket;
    }

    /** Records that a plan was just built. */
    void recordPlan(int newChunkX, int newChunkZ, int newDimension, long now) {
        this.chunkX = newChunkX;
        this.chunkZ = newChunkZ;
        this.dimension = newDimension;
        this.lastPlanTick = now;
    }

    /**
     * True when the ownership log line should be emitted.
     *
     * <p>A line is written when the managed/player-owned counts change, or when
     * chunks were loaded, or every {@link #LOG_INTERVAL_TICKS} at most.</p>
     */
    boolean shouldLog(int managed, int playerOwned, int loadedNow, long now) {
        boolean changed = managed != this.lastManaged || playerOwned != this.lastPlayerOwned;
        if (loadedNow == 0 && !changed && elapsed(now, this.lastLogTick) < LOG_INTERVAL_TICKS) {
            return false;
        }
        this.lastLogTick = now;
        this.lastManaged = managed;
        this.lastPlayerOwned = playerOwned;
        return true;
    }

    /**
     * Tick difference that is safe against the initial {@code Long.MIN_VALUE}.
     *
     * <p>A fresh cursor must look "due", not overflow to a huge negative delay.</p>
     */
    private static long elapsed(long now, long then) {
        return then == Long.MIN_VALUE ? Long.MAX_VALUE : now - then;
    }
}
