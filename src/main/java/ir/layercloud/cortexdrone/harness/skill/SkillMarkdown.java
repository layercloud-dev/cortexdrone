package ir.layercloud.cortexdrone.harness.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reader and writer for the {@code SKILL.md} format used by bundled and learned
 * skills.
 *
 * <p>A skill directory contains:</p>
 *
 * <pre>
 * skills/guard/
 *   SKILL.md   YAML frontmatter + human/agent-readable instructions
 *   code.js    JavaScript body executed with `params` in scope
 * </pre>
 *
 * <p>The parser intentionally supports a small YAML subset: scalar keys and
 * block/inline lists. That is enough for skill metadata and keeps the mod free
 * of a YAML dependency.</p>
 */
public final class SkillMarkdown {
    public static final String FILE_NAME = "SKILL.md";
    public static final String DEFAULT_CODE_FILE = "code.js";

    private SkillMarkdown() {
    }

    /** Parses one SKILL.md document. Returns {@code null} when it is empty. */
    public static Skill read(String text, String source) {
        if (text == null || text.trim().isEmpty()) return null;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        Skill skill = new Skill();
        if (source != null) skill.setSource(source);
        skill.setName("");
        skill.setDescription("");
        skill.setTags("");
        skill.setTriggers(new ArrayList<>());
        skill.setRequires(new ArrayList<>());
        skill.setVersion(1);

        int cursor = 0;
        while (cursor < lines.length && lines[cursor].trim().isEmpty()) cursor++;
        if (cursor >= lines.length || !"---".equals(lines[cursor].trim())) {
            skill.setDocs(normalized.trim());
            return skill;
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
                addListItem(skill, currentList, unquote(trimmed.substring(2).trim()));
                continue;
            }
            int colon = raw.indexOf(':');
            if (colon < 0) continue;
            String key = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = raw.substring(colon + 1).trim();
            currentList = null;
            switch (key) {
                case "triggers":
                case "requires":
                    currentList = key;
                    for (String item : inlineList(value)) addListItem(skill, key, item);
                    break;
                case "name":
                    skill.setName(unquote(value));
                    break;
                case "description":
                    skill.setDescription(unquote(value));
                    break;
                case "tags":
                    skill.setTags(unquote(value));
                    break;
                case "version":
                    skill.setVersion(parseInt(value, 1));
                    break;
                case "uses":
                    skill.setUses(parseInt(value, 0));
                    break;
                case "code":
                case "codefile":
                    if (!value.isEmpty()) skill.setCodeFile(unquote(value));
                    break;
                case "source":
                    if (source == null) skill.setSource(unquote(value));
                    break;
                default:
                    break;
            }
        }

        StringBuilder body = new StringBuilder();
        for (int i = cursor; i < lines.length; i++) {
            body.append(lines[i]);
            if (i + 1 < lines.length) body.append('\n');
        }
        skill.setDocs(body.toString().trim());
        if (skill.getName() == null || skill.getName().trim().isEmpty()) {
            skill.setName(firstHeading(skill.getDocs()));
        }
        if (skill.getDescription().trim().isEmpty()) {
            skill.setDescription(firstParagraph(skill.getDocs()));
        }
        return skill;
    }

    /** Renders a skill directory's SKILL.md, including its usage/docs body. */
    public static String write(Skill skill) {
        StringBuilder out = new StringBuilder(1024);
        out.append("---\n");
        out.append("name: ").append(quote(skill.getName())).append('\n');
        out.append("description: ").append(quote(skill.getDescription())).append('\n');
        out.append("tags: ").append(quote(skill.getTags() != null ? skill.getTags() : "")).append('\n');
        appendList(out, "triggers", skill.getTriggers());
        appendList(out, "requires", skill.getRequires());
        out.append("version: ").append(Math.max(1, skill.getVersion())).append('\n');
        out.append("uses: ").append(Math.max(0, skill.getUses())).append('\n');
        out.append("source: ").append(quote(skill.getSource())).append('\n');
        out.append("code: ").append(DEFAULT_CODE_FILE).append('\n');
        out.append("---\n\n");
        String docs = skill.getDocs();
        if (docs == null || docs.trim().isEmpty()) {
            docs = "# " + skill.getName() + "\n\n" + skill.getDescription() + "\n";
        }
        out.append(docs.trim()).append('\n');
        return out.toString();
    }

    private static void appendList(StringBuilder out, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            out.append(key).append(": []\n");
            return;
        }
        out.append(key).append(":\n");
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            out.append("  - ").append(quote(value.trim())).append('\n');
        }
    }

    private static void addListItem(Skill skill, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if ("triggers".equals(key)) {
            if (skill.getTriggers() == null) skill.setTriggers(new ArrayList<>());
            skill.getTriggers().add(value);
        } else if ("requires".equals(key)) {
            if (skill.getRequires() == null) skill.setRequires(new ArrayList<>());
            skill.getRequires().add(value);
        }
    }

    private static List<String> inlineList(String value) {
        List<String> items = new ArrayList<>();
        if (value == null) return items;
        String clean = value.trim();
        if (clean.isEmpty()) return items;
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        for (String part : clean.split(",")) {
            String item = unquote(part.trim());
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    private static String unquote(String value) {
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

    private static String quote(String value) {
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

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(unquote(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstHeading(String docs) {
        if (docs == null) return "";
        for (String line : docs.split("\n")) {
            String clean = line.trim();
            if (clean.startsWith("#")) return clean.replaceFirst("^#+\\s*", "").trim();
        }
        return "";
    }

    private static String firstParagraph(String docs) {
        if (docs == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : docs.split("\n")) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.startsWith("#")) {
                if (out.length() > 0) break;
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(clean);
            if (out.length() > 260) break;
        }
        String result = out.toString();
        return result.length() > 260 ? result.substring(0, 257) + "..." : result;
    }
}
