package ir.layercloud.cortexdrone.harness.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat-safe text shaping for agent replies.
 *
 * <p>Extracted from {@code AgentService}. Minecraft chat lines are short, so a
 * long reply is split on word boundaries where possible and never mid-word
 * unless there is no choice.</p>
 */
final class ChatLines {
    /** Longest chat line Minecraft renders comfortably. */
    static final int MAX_LINE = 240;
    /** Do not split before this column; prefer a word boundary past it. */
    private static final int MIN_SPLIT = 120;

    private ChatLines() {
    }

    /** Splits a message into chat-sized lines, preserving word boundaries. */
    static List<String> split(String message) {
        List<String> lines = new ArrayList<>();
        if (message == null || message.isEmpty()) return lines;
        String remaining = message;
        while (remaining.length() > MAX_LINE) {
            int cut = remaining.lastIndexOf(' ', MAX_LINE);
            if (cut < MIN_SPLIT) cut = remaining.lastIndexOf('\n', MAX_LINE);
            if (cut < MIN_SPLIT) cut = MAX_LINE;
            lines.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) lines.add(remaining);
        return lines;
    }

    /** Collapses whitespace and truncates with an ellipsis. */
    static String limit(String text, int max) {
        if (text == null) return "";
        String clean = text.trim().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    /** A stable skill name derived from the prompt, unique for non-Latin text. */
    static String skillName(String prompt) {
        if (prompt == null) return "task_workflow";
        String[] words = prompt.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ").trim().split("\\s+");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < words.length && i < 5; i++) {
            if (words[i].isEmpty()) continue;
            if (name.length() > 0) name.append('_');
            name.append(words[i]);
        }
        String result = name.toString();
        // Non-Latin prompts normalize to nothing; keep skills distinct instead
        // of letting every such task overwrite one "task_workflow" entry.
        if (result.length() < 4) {
            result = (result.isEmpty() ? "workflow" : result) + "_"
                    + Integer.toHexString(prompt.hashCode());
        }
        return result.length() > 48 ? result.substring(0, 48) : result;
    }
}
