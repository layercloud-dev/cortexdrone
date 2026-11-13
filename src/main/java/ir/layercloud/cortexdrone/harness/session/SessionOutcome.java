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
    /**
     * The model produced a final answer that did not report a problem.
     */
    SUCCESS("success", "success"),
    /**
     * The model finished but reported that the task could not be done.
     */
    FAILURE("failure", "failure"),
    /**
     * The player or the model explicitly cancelled the task.
     */
    CANCELLED("cancelled", "cancelled"),
    /**
     * The task was replaced, despawned, or the server stopped mid-run.
     */
    INTERRUPTED("interrupted", "interrupted"),
    /**
     * Turn/time budget or an empty model response ended the loop.
     */
    INCOMPLETE("incomplete", "incomplete"),
    /**
     * LLM/backend/internal exception ended the loop.
     */
    ERROR("error", "error");

    private static final String[] FAILURE_MARKERS = {
            "i couldn't", "i could not", "i can't", "i cannot", "i was unable",
            "i am unable", "unable to", "failed to", "gave up", "blocked by",
            "not possible", "impossible", "no route", "no safe", "not enough",
            "no access", "no room", "denied", "could not be", "couldn't be",
            "not found"
    };

    /**
     * Explicit signal a final answer may carry, e.g. {@code OUTCOME: failure}.
     *
     * <p>The model knows whether the task succeeded; text heuristics do not.
     * When the signal is present it wins outright, so a confident-sounding
     * failure and a report that merely mentions an earlier setback are both
     * filed correctly.</p>
     */
    private static final java.util.regex.Pattern EXPLICIT =
            java.util.regex.Pattern.compile(
                    "(?i)\\boutcome\\s*[:=]\\s*[*_`]*(success|succeeded|done|failure|failed"
                            + "|blocked|incomplete|partial)\\b");

    /**
     * Sentence boundary: a newline (list items are one sentence each), a period
     * followed by space and a capital, or a closing punctuation mark.
     */
    private static final java.util.regex.Pattern SENTENCE =
            java.util.regex.Pattern.compile("(?<=[.!?])\\s+|\\n+");

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

    /**
     * Classification of a final model reply.
     *
     * <p>Order of authority:</p>
     * <ol>
     *   <li>an explicit {@code OUTCOME: success|failure|...} line, because the
     *       model knows the real result and a text scan does not;</li>
     *   <li>a task-failure sentence — one that names the requested work as
     *       blocked. A report that only mentions a past attempt ("a few failed
     *       attempts, then 60 ore mined") is not a failure;</li>
     *   <li>otherwise success.</li>
     * </ol>
     */
    public static SessionOutcome fromReply(String reply) {
        if (reply == null || reply.trim().isEmpty()) return INCOMPLETE;
        SessionOutcome explicit = explicitOutcome(reply);
        if (explicit != null) return explicit;
        for (String sentence : SENTENCE.split(reply.toLowerCase(Locale.ROOT))) {
            if (sentence.isEmpty()) continue;
            if (mentionsASetback(sentence)) continue;
            for (String marker : FAILURE_MARKERS) {
                if (sentence.contains(marker)) return FAILURE;
            }
        }
        return SUCCESS;
    }

    /**
     * The outcome named by an explicit signal, or {@code null}.
     */
    public static SessionOutcome explicitOutcome(String reply) {
        if (reply == null) return null;
        java.util.regex.Matcher matcher = EXPLICIT.matcher(reply);
        if (!matcher.find()) return null;
        String signal = matcher.group(1).toLowerCase(Locale.ROOT);
        if (signal.startsWith("success") || "done".equals(signal)) return SUCCESS;
        if (signal.startsWith("fail") || "blocked".equals(signal)) return FAILURE;
        return INCOMPLETE;
    }

    /**
     * True when the sentence is about a past attempt rather than the task
     * result, so its failure wording must not decide the outcome.
     */
    private static boolean mentionsASetback(String sentence) {
        return sentence.contains("attempt")
                || sentence.contains("retry")
                || sentence.contains("retried")
                || sentence.contains("after a few")
                || sentence.contains("initially")
                || sentence.contains("at first");
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
