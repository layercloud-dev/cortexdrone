package ir.layercloud.cortexdrone.service.code;

import ir.layercloud.cortexdrone.config.ModConfig;

/**
 * Bounds for the code-execution tool.
 *
 * <p>Regression: {@code 1_000}/{@code 300_000}/{@code 120_000} were repeated in
 * the schema and its prose, while {@link ModConfig} already owned the default.</p>
 */
public final class CodeLimits {
    private CodeLimits() {
    }

    /** Smallest code-execution budget. */
    public static final int MIN_TIMEOUT_MS = 1_000;
    /** Largest code-execution budget. */
    public static final int MAX_TIMEOUT_MS = 300_000;
    /** Default code-execution budget; {@link ModConfig} is the owner. */
    public static final int DEFAULT_TIMEOUT_MS = ModConfig.DEFAULT_EXECUTE_TIMEOUT_MS;
    /** Maximum Rhino instructions per execution. */
    public static final int MAX_INSTRUCTIONS = 20_000;
}
