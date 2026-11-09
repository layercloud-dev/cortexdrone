package ir.layercloud.cortexdrone.service.navigation;

/**
 * Navigation policy bounds shared by the tool schema, the view and the session.
 *
 * <p>Regression: the dig allowance default ({@code 128}) and ceiling
 * ({@code 512}) were repeated in the schema, the argument view and the session
 * setup. A change to one left the others stale.</p>
 */
public final class NavigationLimits {
    private NavigationLimits() {
    }

    /** No digging is always allowed. */
    public static final int MIN_DIG_BLOCKS = 0;
    /** Largest number of blocks a single navigation call may clear. */
    public static final int MAX_DIG_BLOCKS = 512;
    /** Dig allowance when the caller names none. */
    public static final int DEFAULT_DIG_BLOCKS = 128;

    /** Smallest wait budget a goto call accepts. */
    public static final int MIN_WAIT_MS = 5_000;
    /** Largest wait budget a goto call accepts. */
    public static final int MAX_WAIT_MS = 180_000;
    /** Wait budget when the caller names none. */
    public static final int DEFAULT_WAIT_MS = 120_000;

    /** Smallest recovery-attempt budget a goto call accepts. */
    public static final int MIN_RECOVERY_ATTEMPTS = 0;
    /** Largest recovery-attempt budget a goto call accepts. */
    public static final int MAX_RECOVERY_ATTEMPTS = 32;
    /** Recovery attempts when the caller names none. */
    public static final int DEFAULT_RECOVERY_ATTEMPTS = 12;

    /** Clamps a requested dig allowance into the supported range. */
    public static int clampDigBlocks(int requested) {
        return Math.max(MIN_DIG_BLOCKS, Math.min(MAX_DIG_BLOCKS, requested));
    }
}
