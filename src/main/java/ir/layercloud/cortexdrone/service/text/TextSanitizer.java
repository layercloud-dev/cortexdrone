package ir.layercloud.cortexdrone.service.text;

/**
 * One owner for "text that is safe to show".
 *
 * <p>Model output, player chat and network payloads are untrusted. Minecraft
 * 1.7.10 renders one UTF-16 {@code char} at a time, so a surrogate pair (emoji)
 * becomes two missing glyphs, and {@code §} is a live formatting code: a reply
 * could recolour chat, inject a fake prefix, or hide text. Control characters
 * break the one-line log format, and bidi overrides can reverse text in chat.
 * This class strips those while preserving legitimate Unicode, including
 * Persian (ZWNJ U+200C and ZWJ U+200D are kept).</p>
 */
@SuppressWarnings("unused")
public final class TextSanitizer {
    /**
     * Minecraft formatting-code prefix.
     */
    public static final char FORMATTING = '\u00A7';

    private TextSanitizer() {
    }

    /**
     * Single-line text for chat, status lines and logs.
     */
    public static String chat(String value) {
        return tidy(clean(value, false), false);
    }

    /**
     * Single-line text capped at {@code max} characters.
     */
    public static String chat(String value, int max) {
        return truncate(chat(value), max);
    }

    /**
     * Multi-line text for the question dialog; line breaks are preserved.
     */
    public static String dialog(String value) {
        return tidy(clean(value, true), true);
    }

    /**
     * Multi-line text capped at {@code max} characters.
     */
    public static String dialog(String value, int max) {
        return truncate(dialog(value), max);
    }

    /**
     * Single-line text for the debug log file.
     */
    public static String log(String value) {
        return chat(value);
    }

    public static String log(String value, int max) {
        return truncate(log(value), max);
    }

    /**
     * Cuts at most {@code max} characters after sanitizing.
     */
    public static String truncate(String value, int max) {
        if (value == null) return "";
        if (max <= 0) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String clean(String value, boolean keepNewlines) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                // Drop the whole pair: 1.7.10 cannot render non-BMP glyphs.
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            if (c == FORMATTING) {
                // A formatting code is § + one code character; drop both so the
                // letter does not become visible text.
                if (i + 1 < value.length()) i++;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                if (keepNewlines) {
                    if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
                    out.append('\n');
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (c == '\t' || c == '\u00A0') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) continue;
            if (isInvisible(c)) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static String tidy(String value, boolean keepNewlines) {
        StringBuilder out = new StringBuilder(value.length());
        boolean lastSpace = false;
        int newlines = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                if (!keepNewlines) {
                    if (!lastSpace) out.append(' ');
                    lastSpace = true;
                    continue;
                }
                while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
                    out.setLength(out.length() - 1);
                }
                if (newlines < 2) out.append('\n');
                newlines++;
                lastSpace = false;
                continue;
            }
            if (c == ' ') {
                if (!lastSpace) out.append(' ');
                lastSpace = true;
                continue;
            }
            out.append(c);
            lastSpace = false;
            newlines = 0;
        }
        return out.toString().trim();
    }

    /**
     * Zero-width and bidi controls that can hide or reorder visible text.
     * ZWNJ/ZWJ (U+200C/U+200D) stay because Persian and other scripts need them.
     */
    private static boolean isInvisible(char c) {
        if (c == '\u200B' || c == '\u200E' || c == '\u200F') return true;
        if (c == '\u00AD' || c == '\uFEFF') return true;
        if (c >= '\u202A' && c <= '\u202E') return true;
        if (c >= '\u2060' && c <= '\u2064') return true;
        if (c >= '\u2066' && c <= '\u206F') return true;
        return c >= '\uFE00' && c <= '\uFE0F';
    }
}
