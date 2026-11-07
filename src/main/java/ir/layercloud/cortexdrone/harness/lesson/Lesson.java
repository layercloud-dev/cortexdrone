package ir.layercloud.cortexdrone.harness.lesson;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One durable lesson learned from a real attempt.
 *
 * <p>A lesson is procedural knowledge, not a player fact: "breaking downward
 * under the drone makes it fall", "the iron farm needs a standoff at y+1",
 * "always withdraw buckets before scooping lava". Facts and named locations
 * belong to {@code MemoryStore} instead.</p>
 */
@Data
public class Lesson {
    private String id;
    private String scope = "general";
    private String title = "";
    private String body = "";
    private List<String> tags = new ArrayList<>();
    /** success, failure or note. */
    private String outcome = "note";
    private boolean pinned;
    private int uses;
    private String source = "agent";
    private int version = 1;

    private long createdAt;
    private long updatedAt;

    /** Absolute LESSON.md directory; transient, never persisted. */
    private transient String path;

    public void touch() {
        long now = System.currentTimeMillis();
        if (this.createdAt <= 0) this.createdAt = now;
        this.updatedAt = now;
    }

    /** One-line summary used in the prompt context. */
    public String summary(int max) {
        String text = this.body != null && !this.body.trim().isEmpty() ? this.body : this.title;
        if (text == null) return "";
        String clean = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }
}
