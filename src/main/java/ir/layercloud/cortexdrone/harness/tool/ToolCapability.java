package ir.layercloud.cortexdrone.harness.tool;

/**
 * Behavioural capabilities of a tool.
 *
 * <p>Several consumers used to hard-code tool-name lists to answer "does this
 * change the world?", "does this finish asynchronously?" and "is this a
 * transfer?". A list in each consumer drifts the moment a tool is added, so the
 * answer belongs on the declaration itself.</p>
 *
 * <p>This is intentionally coarse: it is metadata for reporting, waiting and
 * safety classification, not a permissions system.</p>
 */
public enum ToolCapability {
    /** Changes blocks or entities in the world. */
    MUTATES_WORLD,
    /** Moves items between inventories. */
    MUTATES_INVENTORY,
    /** Changes only the drone's own presentation (LED, particles, chat). */
    PRESENTATION,
    /** Starts a server-side job and only resolves once it finishes. */
    AWAITS_COMPLETION,
    /** Resolves only when the navigation job reaches its destination or fails. */
    AWAITS_NAVIGATION,
    /** Returns a pollable job id instead of a finished result. */
    TIME_SLICED,
    /** Stays inline while its radius is at or below the time-slice threshold. */
    INLINE_WHEN_SMALL,
    /** Reads the world without changing it. */
    READ_ONLY
}
