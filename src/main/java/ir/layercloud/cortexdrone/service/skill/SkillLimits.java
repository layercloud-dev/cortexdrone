package ir.layercloud.cortexdrone.service.skill;

/**
 * Time budgets for the skill runner and its wrapper tools.
 *
 * <p>Regression: {@code 180_000}/{@code 280_000} were repeated in the schema,
 * the wrapper timeout and the runtime fallback.</p>
 */
public final class SkillLimits {
    private SkillLimits() {
    }

    /** Smallest wall-clock budget a caller may request. */
    public static final int MIN_TIMEOUT_MS = 1_000;
    /** Largest wall-clock budget a caller may request. */
    public static final int MAX_TIMEOUT_MS = 280_000;
    /** Budget used when the caller names none. */
    public static final int DEFAULT_TIMEOUT_MS = 180_000;
    /** Wrapper timeout for the {@code skill} tool itself. */
    public static final int EXECUTE_TIMEOUT_MS = 300_000;
}
