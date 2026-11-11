package ir.layercloud.cortexdrone.service.world;

import java.util.List;
import java.util.Locale;

/**
 * Text helpers for OreDictionary tag matching.
 *
 * <p>Extracted from {@code OreDictResolver}. Tag matching has to tolerate the
 * many spellings a player or model uses for the same material
 * ({@code ore_iron}, {@code ore iron}, {@code oreIron}), so these helpers define
 * exactly how two spellings are considered equal.</p>
 */
final class OreDictText {
    private OreDictText() {
    }

    /**
     * Lower-case and trim; the canonical form for comparisons.
     */
    static String normalizeRaw(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Lowercase letters and digits only, so {@code ore_iron} and {@code oreIron}
     * collide.
     */
    static String compactKey(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * Joins tokens with {@code |} for a cache key.
     */
    static String joinTokens(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append('|');
            out.append(token);
        }
        return out.toString();
    }

    /**
     * True when {@code value} starts with any of {@code prefixes}.
     */
    static boolean startsWithAny(String value, String[] prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * True when {@code word} occurs in {@code text} on word boundaries.
     */
    static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int index = text.indexOf(word, from);
            if (index < 0) return false;
            boolean leftOk = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            int end = index + word.length();
            boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftOk && rightOk) return true;
            from = index + 1;
        }
    }
}
