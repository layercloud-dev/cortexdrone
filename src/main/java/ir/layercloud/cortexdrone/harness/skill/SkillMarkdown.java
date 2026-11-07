package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.harness.markdown.Frontmatter;

import java.util.List;

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
 * <p>Parsing is delegated to {@link Frontmatter}; the body is kept as the
 * skill's {@code docs} so the model can read parameters and pitfalls without
 * loading the code first.</p>
 */
public final class SkillMarkdown {
    public static final String FILE_NAME = "SKILL.md";
    public static final String DEFAULT_CODE_FILE = "code.js";

    private SkillMarkdown() {
    }

    /** Parses one SKILL.md document. Returns {@code null} when it is empty. */
    public static Skill read(String text, String source) {
        if (text == null || text.trim().isEmpty()) return null;
        Frontmatter.Document doc = Frontmatter.parse(text);

        Skill skill = new Skill();
        if (source != null) skill.setSource(source);
        skill.setName(doc.scalar("name", ""));
        skill.setDescription(doc.scalar("description", ""));
        skill.setTags(doc.scalar("tags", ""));
        skill.setTriggers(doc.list("triggers"));
        skill.setRequires(doc.list("requires"));
        skill.setVersion(doc.integer("version", 1));
        skill.setUses(doc.integer("uses", 0));

        String codeFile = doc.scalar("code", doc.scalar("codefile", ""));
        if (!codeFile.trim().isEmpty()) skill.setCodeFile(codeFile.trim());
        if (source == null) {
            String declaredSource = doc.scalar("source", "");
            if (!declaredSource.trim().isEmpty()) skill.setSource(declaredSource.trim());
        }

        skill.setDocs(doc.body());
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
        out.append("name: ").append(Frontmatter.quote(skill.getName())).append('\n');
        out.append("description: ").append(Frontmatter.quote(skill.getDescription())).append('\n');
        out.append("tags: ").append(Frontmatter.quote(skill.getTags() != null ? skill.getTags() : "")).append('\n');
        appendList(out, "triggers", skill.getTriggers());
        appendList(out, "requires", skill.getRequires());
        out.append("version: ").append(Math.max(1, skill.getVersion())).append('\n');
        out.append("uses: ").append(Math.max(0, skill.getUses())).append('\n');
        out.append("source: ").append(Frontmatter.quote(skill.getSource())).append('\n');
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
            out.append("  - ").append(Frontmatter.quote(value.trim())).append('\n');
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
