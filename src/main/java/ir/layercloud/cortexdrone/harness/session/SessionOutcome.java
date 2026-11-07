package ir.layercloud.cortexdrone.harness.session;

import java.util.Locale;

/**
 * Terminal category of one agent session (one {@code @drone} task loop).
 *
 * <p>Every session is written to disk, but the category decides the folder and
 * the index entry so successful runs, failures and dropped runs can be reviewed
 * separately.</p>
 */
public enum SessionOutcome {
    /** The model produced a final answer that did not report a problem. */
    SUCCESS("success", "success"),
    /** The model finished but reported that the task could not be done. */
    FAILURE("failure", "failure"),
    /** The player or the model explicitly cancelled the task. */
    CANCELLED("cancelled", "cancelled"),
    /** The task was replaced, despawned, or the server stopped mid-run. */
    INTERRUPTED("interrupted", "interrupted"),
    /** Turn/time budget or an empty model response ended the loop. */
    INCOMPLETE("incomplete", "incomplete"),
    /** LLM/backend/internal exception ended the loop. */
    ERROR("error", "error");

    private static final String[] FAILURE_MARKERS = {
            "couldn't", "could not", "can't", "cannot", "unable to", "failed", "failure",
            "blocked by", "impossible", "not possible", "no route", "not enough",
            "missing", "denied", "not found", "gave up", "no safe",
            "no access", "no room"
    };

    private final String id;
    private final String directory;

    SessionOutcome(String id, String directory) {
        this.id = id;
        this.directory = directory;
    }

    public String id() {
        return this.id;
    }

    public String directory() {
        return this.directory;
    }

    /** Heuristic classification of a final model reply. */
    public static SessionOutcome fromReply(String reply) {
        if (reply == null || reply.trim().isEmpty()) return INCOMPLETE;
        String lower = reply.toLowerCase(Locale.ROOT);
        for (String marker : FAILURE_MARKERS) {
            if (lower.contains(marker)) return FAILURE;
        }
        return SUCCESS;
    }

    public static SessionOutcome fromId(String value) {
        if (value == null) return null;
        String clean = value.trim().toLowerCase(Locale.ROOT);
        for (SessionOutcome outcome : values()) {
            if (outcome.id.equals(clean) || outcome.directory.equals(clean)
                    || outcome.name().toLowerCase(Locale.ROOT).equals(clean)) {
                return outcome;
            }
        }
        return null;
    }
}
