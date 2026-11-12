package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Derives a coarse task topic from the prompt and the transcript.
 *
 * <p>Extracted from {@link SessionJournal}: the keyword table is the only part
 * of the journal with domain vocabulary, and keeping it separate makes the
 * classification rules explicit and testable.</p>
 */
public final class SessionTopics {
    /**
     * Ordered rules: the first match wins.
     */
    private static final Rule[] PROMPT_RULES = {
            new Rule("mining", "mine", "mining", "dig", "digging", "ore", "diamond", "coal",
                    "iron", "gold", "quarry", "excavat", "tunnel"),
            new Rule("building", "build", "building", "place", "wall", "house", "tower",
                    "platform", "bridge", "roof", "floor", "farm"),
            new Rule("navigation", "go to", "goto", "travel", "fly", "navigate", "return",
                    "come to", "waypoint", "base", "home"),
            new Rule("inventory", "inventory", "chest", "item", "store", "deposit",
                    "withdraw", "give", "take", "drop", "craft", "sort"),
            new Rule("combat", "attack", "kill", "fight", "mob", "zombie", "skeleton",
                    "creeper", "combat", "defend"),
            new Rule("survival", "survive", "escape", "safe", "emergency", "lava", "fire", "fall"),
    };

    /**
     * Fallback rules keyed on the tool names actually used.
     */
    private static final Rule[] TOOL_RULES = {
            new Rule("navigation", "goto", "breadcrumbs"),
            new Rule("mining", "break_block", "scan_blocks", "scan_veins"),
            new Rule("building", "place_block"),
            new Rule("inventory", "craft", "deposit_chest", "withdraw_chest", "give_to_player",
                    "take_from_player", "move_item"),
    };

    private SessionTopics() {
    }

    /**
     * Classifies the topic, defaulting to {@code general}.
     */
    public static String classify(String prompt, List<JsonObject> transcript) {
        String text = prompt != null ? prompt.toLowerCase(Locale.ROOT) : "";
        for (Rule rule : PROMPT_RULES) {
            if (rule.matches(text)) return rule.topic;
        }
        // Fall back to the tool surface when the prompt is vague or non-Latin.
        String tools = transcript != null ? transcript.toString().toLowerCase(Locale.ROOT) : "";
        for (Rule rule : TOOL_RULES) {
            if (rule.matches(tools)) return rule.topic;
        }
        return "general";
    }

    /**
     * Every topic the classifier can return, for validation and docs.
     */
    public static List<String> known() {
        List<String> topics = new ArrayList<>();
        topics.add("mining");
        topics.add("building");
        topics.add("navigation");
        topics.add("inventory");
        topics.add("combat");
        topics.add("survival");
        topics.add("general");
        return topics;
    }

    private static final class Rule {
        final String topic;
        final String[] needles;

        Rule(String topic, String... needles) {
            this.topic = topic;
            this.needles = needles;
        }

        boolean matches(String text) {
            for (String needle : this.needles) {
                if (text.contains(needle)) return true;
            }
            return false;
        }
    }
}
