package ir.layercloud.cortexdrone.harness.markdown;

import java.util.*;

/**
 * Minimal YAML frontmatter reader shared by skills and lessons.
 *
 * <p>Documents look like:</p>
 *
 * <pre>
 * ---
 * key: "value"
 * list:
 *   - "one"
 *   - "two"
 * ---
 * Markdown body
 * </pre>
 *
 * <p>Only the subset needed by the mod is supported: quoted/unquoted scalars,
 * inline {@code [a, b]} lists and block lists. Everything after the closing
 * delimiter is the Markdown body, exactly like the normal SKILL.md layout.</p>
 */
@SuppressWarnings("unused")
public final class Frontmatter {
    private Frontmatter() {
    }

    /**
     * Parsed document: scalar fields, list fields and the Markdown body.
     */
    public static final class Document {
        private final Map<String, String> values;
        private final Map<String, List<String>> lists;
        private final String body;

        private Document(Map<String, String> values, Map<String, List<String>> lists, String body) {
            this.values = values;
            this.lists = lists;
            this.body = body;
        }

        public boolean has(String key) {
            String clean = normalizeKey(key);
            return this.values.containsKey(clean) || this.lists.containsKey(clean);
        }

        public String scalar(String key, String fallback) {
            String value = this.values.get(normalizeKey(key));
            return value != null ? value : fallback;
        }

        public List<String> list(String key) {
            List<String> value = this.lists.get(normalizeKey(key));
            return value != null ? new ArrayList<>(value) : new ArrayList<>();
        }

        public int integer(String key, int fallback) {
            return parseInt(this.values.get(normalizeKey(key)), fallback);
        }

        public long longValue(String key, long fallback) {
            String value = this.values.get(normalizeKey(key));
            if (value == null || value.trim().isEmpty()) return fallback;
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        public boolean bool(String key, boolean fallback) {
            String value = this.values.get(normalizeKey(key));
            if (value == null) return fallback;
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (clean.equals("true") || clean.equals("yes") || clean.equals("on") || clean.equals("1")) return true;
            if (clean.equals("false") || clean.equals("no") || clean.equals("off") || clean.equals("0")) return false;
            return fallback;
        }

        public String body() {
            return this.body;
        }
    }

    public static Document parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return new Document(values, lists, "");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        // Windows Notepad saves UTF-8 with a BOM. trim() does not remove U+FEFF,
        // so the first line became "\uFEFF---" and the whole YAML block was
        // treated as the Markdown body: name/tags/triggers were lost.
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }
        String[] lines = normalized.split("\n", -1);

        int cursor = 0;
        while (cursor < lines.length && lines[cursor].trim().isEmpty()) cursor++;
        if (cursor >= lines.length || !"---".equals(lines[cursor].trim())) {
            return new Document(values, lists, normalized.trim());
        }

        cursor++;
        String currentList = null;
        for (; cursor < lines.length; cursor++) {
            String raw = lines[cursor];
            String trimmed = raw.trim();
            if ("---".equals(trimmed)) {
                cursor++;
                break;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("- ") && currentList != null) {
                addListValue(lists, currentList, unquote(trimmed.substring(2).trim()));
                continue;
            }
            int colon = raw.indexOf(':');
            if (colon < 0) continue;
            String key = normalizeKey(raw.substring(0, colon));
            String value = raw.substring(colon + 1).trim();
            currentList = null;
            if (value.isEmpty()) {
                currentList = key;
                lists.computeIfAbsent(key, ignored -> new ArrayList<>());
                values.remove(key);
                continue;
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = lists.computeIfAbsent(key, ignored -> new ArrayList<>());
                items.clear();
                String inner = value.substring(1, value.length() - 1);
                for (String part : inner.split(",")) {
                    String item = unquote(part.trim());
                    if (!item.isEmpty()) items.add(item);
                }
                values.remove(key);
                continue;
            }
            values.put(key, unquote(value));
            lists.remove(key);
        }

        StringBuilder body = new StringBuilder();
        for (int i = cursor; i < lines.length; i++) {
            body.append(lines[i]);
            if (i + 1 < lines.length) body.append('\n');
        }
        return new Document(values, lists, body.toString().trim());
    }

    /**
     * Quotes a YAML scalar so it always round-trips through {@link #parse}.
     */
    public static String quote(String value) {
        String text = value != null ? value : "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '"') out.append("\\\"");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append(' ');
            else out.append(c);
        }
        out.append('"');
        return out.toString();
    }

    public static String unquote(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            String inner = text.substring(1, text.length() - 1);
            StringBuilder out = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char next = inner.charAt(++i);
                    if (next == 'n') out.append('\n');
                    else out.append(next);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    public static List<String> splitInlineList(String value) {
        List<String> items = new ArrayList<>();
        if (value == null) return items;
        String clean = value.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.isEmpty()) return items;
        for (String part : clean.split(",")) {
            String item = unquote(part.trim());
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    static void addListValue(Map<String, List<String>> lists, String key, String value) {
        if (value == null || value.isEmpty()) return;
        lists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    private static String normalizeKey(String key) {
        return key != null ? key.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
