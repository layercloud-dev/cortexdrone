package ir.layercloud.cortexdrone;

import java.util.Locale;

/**
 * Extracts a drone instruction from a chat line.
 *
 * <p>Two forms are accepted: the global {@code @drone <message>} prefix, and
 * {@code @<droneName> <message>} for a specific drone. Parsing is pure so the
 * prefix rules can be tested without a chat event or a live player.</p>
 */
final class ChatInstruction {
    /** Global command prefix. */
    static final String GLOBAL_PREFIX = "@drone";
    /** Message used when the player only types the prefix. */
    static final String DEFAULT_MESSAGE = "status";

    private ChatInstruction() {
    }

    /**
     * @param message      the raw chat line
     * @param droneName    the sender's drone name, if they own one
     * @return the instruction, or {@code null} when this line is not for a drone
     */
    static String parse(String message, String droneName) {
        if (message == null) return null;
        String text = message.trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.equals(GLOBAL_PREFIX)) return DEFAULT_MESSAGE;
        if (lower.startsWith(GLOBAL_PREFIX + " ")) {
            String rest = text.substring(GLOBAL_PREFIX.length()).trim();
            return rest.isEmpty() ? DEFAULT_MESSAGE : rest;
        }
        if (droneName == null || droneName.isEmpty()) return null;
        String named = "@" + droneName.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(named)) return null;
        // The prefix must end at a boundary so "@Miner" does not match "@Miner2".
        if (lower.length() > named.length()
                && !Character.isWhitespace(lower.charAt(named.length()))) {
            return null;
        }
        String rest = text.substring(named.length()).trim();
        return rest.isEmpty() ? DEFAULT_MESSAGE : rest;
    }
}
