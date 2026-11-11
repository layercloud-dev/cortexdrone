package ir.layercloud.cortexdrone.harness.tool;

/**
 * Wrapper timeouts shared by tool providers.
 *
 * <p>Regression: {@code 120_000L}/{@code 180_000L}/{@code 300_000L} were
 * repeated across block, inventory, navigation and code tools.</p>
 */
public final class ToolTimeouts {
    private ToolTimeouts() {
    }

    /**
     * Smallest probe/read budget.
     */
    public static final long FASTEST_MS = 3_000L;
    /**
     * Fast read budget.
     */
    public static final long FAST_MS = 4_000L;
    /**
     * Standard read budget.
     */
    public static final long READ_MS = 5_000L;
    /**
     * Scan budget.
     */
    public static final long SCAN_MS = 6_000L;
    /**
     * Survey/plan budget.
     */
    public static final long SURVEY_MS = 8_000L;
    /**
     * Main-thread tool budget.
     */
    public static final long MAIN_MS = 10_000L;
    /**
     * Medium tool budget.
     */
    public static final long MEDIUM_MS = 15_000L;
    /**
     * Web/network budget.
     */
    public static final long WEB_MS = 20_000L;
    /**
     * Action budget.
     */
    public static final long ACTION_MS = 30_000L;
    /**
     * Long action budget.
     */
    public static final long LONG_MS = 60_000L;
    /**
     * Extended action budget.
     */
    public static final long EXTENDED_MS = 90_000L;
    /**
     * Default tool timeout when a spec names none.
     */
    public static final long DEFAULT_TOOL_MS = 8_000L;
    /**
     * Wall-clock budget for a block or inventory action.
     */
    public static final long BLOCK_ACTION_MS = 120_000L;
    /**
     * Wall-clock budget for navigation.
     */
    public static final long NAVIGATION_MS = 180_000L;
    /**
     * Wall-clock budget for a code execution.
     */
    public static final long CODE_EXECUTE_MS = 300_000L;
}
