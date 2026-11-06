package ir.layercloud.cortexdrone.harness;

import java.util.Arrays;
import java.util.List;

/**
 * Deterministic intent classifier for a chat message arriving at a drone that
 * may already be running one task.
 *
 * <p>No language model is involved: the harness must decide deterministically
 * whether a message is a question, a side note, a task modification, or a
 * cancellation before it can decide whether to start/replace/stop the single
 * task session. The classifier is intentionally conservative: when a task is
 * active, anything that is not clearly a question/intent note/cancel is treated
 * as a modification rather than silently ignored.</p>
 */
public final class TaskIntentClassifier {
    private static final List<String> CANCEL_PREFIXES = Arrays.asList(
            "stop", "cancel", "abort", "halt", "quit", "cease", "stand down",
            "never mind", "nevermind", "forget it", "belay that", "shut it down"
    );

    private static final List<String> QUESTION_PREFIXES = Arrays.asList(
            "what", "how", "where", "when", "why", "who", "which",
            "are you", "is it", "do you", "does it", "did you",
            "status", "report", "progress", "update me", "tell me",
            "any update", "how's", "hows", "what's", "whats"
    );

    private static final List<String> BY_THE_WAY_PREFIXES = Arrays.asList(
            "by the way", "btw", "also", "fyi", "just so you know",
            "just a note", "note that", "heads up", "one more thing",
            "while you're at it", "while youre at it"
    );

    private static final List<String> MODIFY_PREFIXES = Arrays.asList(
            "actually", "instead", "change", "modify", "adjust", "add",
            "remove", "make it", "not that", "rather", "wait", "hold on",
            "keep going but", "continue but", "switch", "redo", "on second thought",
            "one change", "small change", "new plan"
    );

    private static final List<String> POLITE_PREFIXES = Arrays.asList(
            "can you", "could you", "would you", "will you", "please", "kindly"
    );

    private static final List<String> ACTION_WORDS = Arrays.asList(
            "mine", "dig", "break", "place", "build", "craft", "smelt", "cook",
            "farm", "harvest", "replant", "plant", "hunt", "kill", "attack",
            "go", "fly", "move", "travel", "bring", "take", "give", "drop",
            "store", "deposit", "collect", "gather", "chop", "cut", "clear",
            "fill", "plug", "scoop", "interact", "use", "equip", "light",
            "explore", "scan", "find", "return", "come", "follow", "guard",
            "patrol", "deliver", "pick", "put", "get", "fetch", "make"
    );

    private TaskIntentClassifier() {
    }

    public static TaskIntent classify(String message, boolean taskActive) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return taskActive ? TaskIntent.QUESTION : TaskIntent.NEW_TASK;
        }
        if (startsWithAny(text, CANCEL_PREFIXES)) {
            return TaskIntent.CANCEL;
        }
        if (isPoliteCancel(text)) {
            return TaskIntent.CANCEL;
        }
        if (startsWithAny(text, QUESTION_PREFIXES)) {
            // "can you mine 5 iron" is a polite command, not a question.
            if (!isPoliteCommand(text)) {
                return TaskIntent.QUESTION;
            }
        }
        if (text.indexOf('?') >= 0 && !isPoliteCommand(text)) {
            return TaskIntent.QUESTION;
        }
        if (startsWithAny(text, BY_THE_WAY_PREFIXES)) {
            return TaskIntent.BY_THE_WAY;
        }
        if (!taskActive) {
            return TaskIntent.NEW_TASK;
        }
        if (startsWithAny(text, MODIFY_PREFIXES) || containsAny(text, MODIFY_PREFIXES)) {
            return TaskIntent.MODIFY;
        }
        if (isPoliteCommand(text) || startsWithAction(text) || containsAny(text, ACTION_WORDS)) {
            return TaskIntent.MODIFY;
        }
        // Unknown side comment while working: keep the task, acknowledge it.
        return TaskIntent.QUESTION;
    }

    public static boolean isPoliteCommand(String message) {
        String text = normalize(message);
        if (!startsWithAny(text, POLITE_PREFIXES)) return false;
        String rest = text;
        for (String p : POLITE_PREFIXES) {
            if (text.startsWith(p)) {
                rest = text.substring(p.length()).trim();
                break;
            }
        }
        return startsWithAction(rest) || containsAny(rest, ACTION_WORDS);
    }

    public static boolean isPoliteCancel(String message) {
        String text = normalize(message);
        if (!startsWithAny(text, POLITE_PREFIXES)) return false;
        String rest = text;
        for (String p : POLITE_PREFIXES) {
            if (text.startsWith(p)) {
                rest = text.substring(p.length()).trim();
                break;
            }
        }
        return startsWithAny(rest, CANCEL_PREFIXES);
    }

    private static boolean startsWithAction(String text) {
        for (String w : ACTION_WORDS) {
            if (text.equals(w) || text.startsWith(w + " ")) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        for (String p : prefixes) {
            if (text.equals(p) || text.startsWith(p + " ") || text.startsWith(p + ",")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            int idx = text.indexOf(w);
            if (idx < 0) continue;
            boolean left = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + w.length();
            boolean right = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static String normalize(String message) {
        if (message == null) return "";
        String text = message.trim().toLowerCase();
        while (text.startsWith("@")) text = text.substring(1).trim();
        return text.replaceAll("\\s+", " ");
    }
}
