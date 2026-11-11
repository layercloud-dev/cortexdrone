package ir.layercloud.cortexdrone.command.impl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which {@code /drone} subcommands need operator rights.
 *
 * <p>Two subcommands cross a trust boundary: {@code js} runs arbitrary Rhino
 * with Java reflection inside the server JVM, and {@code config} can repoint
 * the LLM gateway or replace the API key. Everything else is either read-only
 * or scoped to the sender's own drone, so it stays open to normal players.</p>
 */
public final class CommandPermissions {
    /**
     * Vanilla operator permission level.
     */
    public static final int OP_LEVEL = 2;

    private static final Set<String> OPERATOR_ONLY = new HashSet<>(Arrays.asList(
            "js", "config"
    ));

    private CommandPermissions() {
    }

    /**
     * True when the subcommand must only run for an operator.
     */
    public static boolean requiresOperator(String subcommand) {
        if (subcommand == null) return false;
        return OPERATOR_ONLY.contains(subcommand.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when a permission level is operator-level.
     */
    public static boolean isOperator(int permissionLevel) {
        return permissionLevel >= OP_LEVEL;
    }
}
