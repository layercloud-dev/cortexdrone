package ir.layercloud.cortexdrone.harness.memory;

import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extraction for player memory statements.
 *
 * <p>The agent can always call the {@code memory} tool, but messages such as
 * "remember this is my base" or "keep in mind the farm is at 120 64 -40" must
 * work even while another task is running. This class decides whether a chat
 * message is a memory cue, whether it names a location, and which label and
 * coordinates to use.</p>
 */
public final class MemoryCapture {
    private static final Pattern LOCATION_PHRASE = Pattern.compile(
            "\\b(?:this place|this location|this spot|this area|here|waypoint"
                    + "|coordinates|coords)\\b");
    private static final Pattern COORDS = Pattern.compile(
            "(-?\\d{1,7})[\\s,]+(-?\\d{1,7})[\\s,]+(-?\\d{1,7})");
    private static final Pattern PLACE_IS = Pattern.compile(
            "(?:this|that|the current)\\s+(?:place|location|spot|area)\\s+is\\s+"
                    + "(?:my\\s+|the\\s+|our\\s+)?([a-z0-9][a-z0-9 _-]{1,40})");
    private static final Pattern MY_PLACE = Pattern.compile(
            "\\b(?:my|our|the)\\s+((?:[a-z0-9_-]+\\s+){0,4}"
                    + "(?:base|home|house|farm|mine|shaft|station|portal|workshop|storage|"
                    + "chest\\s+area|waypoint|outpost))\\b");
    private static final Pattern CALL_THIS = Pattern.compile(
            "\\b(?:call|name)\\s+(?:this|it)\\s+(?:place\\s+|location\\s+)?"
                    + "(?:my\\s+|the\\s+|our\\s+)?([a-z0-9][a-z0-9 _-]{1,40})");
    private static final Pattern THIS_IS = Pattern.compile(
            "\\bthis\\s+is\\s+(?:my\\s+|the\\s+|our\\s+)?([a-z0-9][a-z0-9 _-]{1,40})");
    private static final Pattern REMEMBER = Pattern.compile(
            "\\b(?:remember(?:\\s+that)?|keep\\s+in\\s+mind|don'?t\\s+forget|do\\s+not\\s+forget|"
                    + "note\\s+that|save\\s+this\\s+(?:location|place|spot))\\b\\s*(.*)",
            Pattern.CASE_INSENSITIVE);


    private MemoryCapture() {
    }

    /**
     * True when the message starts with an explicit memory instruction.
     */
    public static boolean isMemoryMessage(String message) {
        String text = normalize(message);
        return text.startsWith("remember") || text.startsWith("keep in mind")
                || text.startsWith("don't forget") || text.startsWith("dont forget")
                || text.startsWith("do not forget") || text.startsWith("note that")
                || text.startsWith("this is my") || text.startsWith("this place is")
                || text.startsWith("this location is") || text.startsWith("this spot is")
                || text.startsWith("call this") || text.startsWith("name this")
                || text.startsWith("save this location") || text.startsWith("save this place")
                || text.startsWith("save this spot");
    }

    /**
     * True when a BY_THE_WAY note should also be persisted.
     */
    public static boolean containsMemoryCue(String message) {
        String text = normalize(message);
        return text.contains("remember") || text.contains("keep in mind")
                || text.contains("don't forget") || text.contains("dont forget")
                || text.contains("do not forget")
                || (text.contains("this") && (text.contains("base") || text.contains("home")
                || text.contains("location") || text.contains("place") || text.contains("farm")))
                || text.contains("call this");
    }

    public static boolean isLocation(String message) {
        String text = normalize(message);
        if (COORDS.matcher(text).find()) return true;
        // Word-boundary match: a plain `contains("here")` also matched "where",
        // "there" and "somewhere", so "remember where I died" was stored as a
        // location at the player's current position instead of remembering the
        // text.
        if (LOCATION_PHRASE.matcher(text).find()) return true;
        return MY_PLACE.matcher(text).find()
                || PLACE_IS.matcher(text).find()
                || CALL_THIS.matcher(text).find()
                || THIS_IS.matcher(text).find();
    }

    /**
     * Extracts an explicit "x y z" coordinate triple, or {@code null}.
     */
    public static int[] explicitCoordinates(String message) {
        Matcher matcher = COORDS.matcher(normalize(message));
        if (!matcher.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts a short label such as "base", "iron farm" or "the north mine".
     */
    public static String label(String message) {
        String text = normalize(message);
        Matcher matcher = PLACE_IS.matcher(text);
        if (matcher.find()) return cleanLabel(matcher.group(1));
        matcher = CALL_THIS.matcher(text);
        if (matcher.find()) return cleanLabel(matcher.group(1));
        matcher = THIS_IS.matcher(text);
        if (matcher.find()) return cleanLabel(matcher.group(1));
        matcher = MY_PLACE.matcher(text);
        if (matcher.find()) return cleanLabel(matcher.group(1));
        matcher = REMEMBER.matcher(text);
        if (matcher.find()) {
            String rest = cleanLabel(matcher.group(1));
            if (!rest.isEmpty()) return rest;
        }
        return deriveFromText(text);
    }

    /**
     * Removes the memory prefix so stored text reads naturally.
     */
    public static String canonicalText(String message) {
        String text = normalize(message);
        Matcher matcher = REMEMBER.matcher(text);
        if (matcher.find() && matcher.group(1) != null) {
            String rest = matcher.group(1).trim();
            if (rest.startsWith("to ")) rest = rest.substring(3).trim();
            if (!rest.isEmpty()) return rest;
        }
        return text;
    }

    /**
     * Parses "in the nether" / "in the end" / "overworld" if present.
     */
    public static Integer explicitDimension(String message) {
        String text = normalize(message);
        if (text.contains("nether")) return -1;
        if (text.contains("the end") || text.contains("end dimension")) return 1;
        if (text.contains("overworld") || text.contains("surface")) return 0;
        return null;
    }

    private static String deriveFromText(String text) {
        String stripped = REMEMBER.matcher(text).replaceFirst("$1").trim();
        if (stripped.isEmpty()) stripped = text;
        String[] words = stripped.split("\\s+");
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < words.length && i < 6; i++) {
            if (label.length() > 0) label.append(' ');
            label.append(words[i]);
        }
        return cleanLabel(label.toString());
    }

    private static String cleanLabel(String raw) {
        if (raw == null) return "";
        String value = raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\b(?:is|at|here|please|thanks|ok|okay)\\b", " ")
                .replaceAll("[^a-z0-9 _-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.length() > 48) value = value.substring(0, 48).trim();
        return value;
    }

    public static List<String> tags(String message) {
        List<String> tags = new ArrayList<>();
        for (String token : LexicalItemMatcher.tokenize(message)) {
            if (token.length() >= 3 && !tags.contains(token) && tags.size() < 12) tags.add(token);
        }
        return tags;
    }

    private static String normalize(String message) {
        if (message == null) return "";
        String text = message.trim().toLowerCase(Locale.ROOT);
        while (text.startsWith("@")) text = text.substring(1).trim();
        return text.replaceAll("\\s+", " ");
    }
}
