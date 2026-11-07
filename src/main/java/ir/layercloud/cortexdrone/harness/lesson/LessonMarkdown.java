package ir.layercloud.cortexdrone.harness.lesson;

import ir.layercloud.cortexdrone.harness.markdown.Frontmatter;

import java.util.List;

/**
 * Reader and writer for the {@code LESSON.md} format.
 *
 * <p>Lessons use the same directory + Markdown convention as skills:</p>
 *
 * <pre>
 * lessons/mining-keep-floor-1a2b3c4d/
 *   LESSON.md   frontmatter + symptom -> cause -> rule prose
 * </pre>
 *
 * <p>A lesson is prose, not executable code, so the directory has no script
 * file. The frontmatter keeps the machine-readable metadata and the body is the
 * rule the model reads before a similar task.</p>
 */
public final class LessonMarkdown {
    public static final String FILE_NAME = "LESSON.md";

    private LessonMarkdown() {
    }

    /** Parses one LESSON.md document. Returns {@code null} when it is empty. */
    public static Lesson read(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Frontmatter.Document doc = Frontmatter.parse(text);

        Lesson lesson = new Lesson();
        lesson.setId(doc.scalar("id", ""));
        lesson.setScope(doc.scalar("scope", "general"));
        lesson.setTitle(doc.scalar("title", ""));
        lesson.setOutcome(doc.scalar("outcome", "note"));
        List<String> tags = doc.list("tags");
        lesson.setTags(tags);
        lesson.setPinned(doc.bool("pinned", false));
        lesson.setUses(doc.integer("uses", 0));
        lesson.setSource(doc.scalar("source", "agent"));
        lesson.setVersion(doc.integer("version", 1));
        lesson.setCreatedAt(doc.longValue("created", 0L));
        lesson.setUpdatedAt(doc.longValue("updated", 0L));
        lesson.setBody(doc.body());
        if (lesson.getBody().isEmpty() && !lesson.getTitle().isEmpty()) {
            lesson.setBody(lesson.getTitle());
        }
        return lesson;
    }

    public static String write(Lesson lesson) {
        StringBuilder out = new StringBuilder(768);
        out.append("---\n");
        out.append("id: ").append(Frontmatter.quote(lesson.getId())).append('\n');
        out.append("scope: ").append(Frontmatter.quote(lesson.getScope())).append('\n');
        out.append("title: ").append(Frontmatter.quote(lesson.getTitle())).append('\n');
        out.append("outcome: ").append(Frontmatter.quote(lesson.getOutcome())).append('\n');
        appendList(out, "tags", lesson.getTags());
        out.append("pinned: ").append(lesson.isPinned()).append('\n');
        out.append("uses: ").append(Math.max(0, lesson.getUses())).append('\n');
        out.append("source: ").append(Frontmatter.quote(lesson.getSource())).append('\n');
        out.append("version: ").append(Math.max(1, lesson.getVersion())).append('\n');
        out.append("created: ").append(lesson.getCreatedAt()).append('\n');
        out.append("updated: ").append(lesson.getUpdatedAt()).append('\n');
        out.append("---\n\n");
        String body = lesson.getBody();
        if (body == null || body.trim().isEmpty()) body = lesson.getTitle();
        out.append(body != null ? body.trim() : "").append('\n');
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
}
