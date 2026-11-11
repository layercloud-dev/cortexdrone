package ir.layercloud.cortexdrone.service.question;

/**
 * Bounds for the player-dialogue tool.
 *
 * <p>One owner for the schema prose, the packet clamp and the waiter's poll
 * loop so a question cannot ask for an unbounded answer or wait forever.</p>
 */
public final class QuestionLimits {
    /**
     * Shortest wait; a dialog needs a moment to open and be read.
     */
    public static final int MIN_TIMEOUT_MS = 5_000;
    /**
     * Longest wait; the agent task deadline still applies on top.
     */
    public static final int MAX_TIMEOUT_MS = 300_000;
    /**
     * Default wait for one answer.
     */
    public static final int DEFAULT_TIMEOUT_MS = 120_000;
    /**
     * Quick-pick buttons offered next to the free-text field.
     */
    public static final int MAX_OPTIONS = 4;
    /**
     * Question text shown in the dialog and sent to the client.
     */
    public static final int MAX_QUESTION_CHARS = 400;
    /**
     * One option label; longer labels are cut with an ellipsis.
     */
    public static final int MAX_OPTION_CHARS = 60;
    /**
     * Typed answer; longer text is truncated on both sides.
     */
    public static final int MAX_ANSWER_CHARS = 200;
    /**
     * Question length used for the chat fallback line.
     */
    public static final int CHAT_QUESTION_CHARS = 220;
    /**
     * How long a modded client has to open the dialog before chat is used.
     */
    public static final long CHAT_FALLBACK_MS = 4_000L;
    /**
     * How often the waiter re-checks for an answer, cancellation or logout.
     */
    public static final long POLL_MS = 250L;

    private QuestionLimits() {
    }
}
