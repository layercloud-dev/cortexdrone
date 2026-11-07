package ir.layercloud.cortexdrone.harness.lesson;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.ModPaths;
import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent lessons: what went wrong, why, and the rule for next time.
 *
 * <p>Storage is {@code config/cortexdrone/lessons.json}. Unlike a skill (which
 * is executable code), a lesson is short prose the model reads before acting.
 * Entries are deduplicated by {@code scope + title}, so repeating the same
 * mistake updates the existing lesson instead of duplicating it.</p>
 */
public final class LessonStore {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_ENTRIES = 400;

    private final File storageFile;
    private final Map<String, Lesson> entries = new LinkedHashMap<>();
    private long sequence;

    public LessonStore(File configRoot) {
        this.storageFile = ModPaths.lessonsFile(configRoot);
        load();
    }

    public synchronized Lesson add(String scope, String title, String body,
                                   List<String> tags, String outcome, boolean pinned,
                                   String source) {
        String normalizedScope = normalizeScope(scope);
        String normalizedTitle = normalizeTitle(title);
        String normalizedBody = body != null ? body.trim() : "";
        if (normalizedTitle.isEmpty()) normalizedTitle = normalizeTitle(normalizedBody);
        if (normalizedBody.isEmpty() && !normalizedTitle.isEmpty()) normalizedBody = normalizedTitle;
        if (normalizedTitle.isEmpty() && normalizedBody.isEmpty()) return null;

        Lesson lesson = findByScopeAndTitle(normalizedScope, normalizedTitle);
        if (lesson == null) {
            lesson = new Lesson();
            lesson.setId("les_" + System.currentTimeMillis() + "_" + (++this.sequence));
            lesson.setCreatedAt(System.currentTimeMillis());
            lesson.setVersion(1);
        } else {
            lesson.setVersion(lesson.getVersion() + 1);
        }
        lesson.setScope(normalizedScope);
        lesson.setTitle(normalizedTitle);
        lesson.setBody(normalizedBody);
        lesson.setOutcome(normalizeOutcome(outcome));
        if (tags != null && !tags.isEmpty()) {
            List<String> merged = new ArrayList<>(lesson.getTags() != null ? lesson.getTags() : Collections.emptyList());
            for (String tag : tags) {
                String value = tag != null ? tag.trim().toLowerCase(Locale.ROOT) : "";
                if (!value.isEmpty() && !merged.contains(value)) merged.add(value);
            }
            lesson.setTags(limitTags(merged));
        }
        lesson.setPinned(pinned || lesson.isPinned());
        lesson.setSource(source != null ? source : "agent");
        lesson.touch();
        this.entries.put(lesson.getId(), lesson);
        enforceLimit();
        save();
        return lesson;
    }

    public synchronized List<Lesson> search(String query, String scope, int limit) {
        List<Lesson> all = filtered(scope);
        String clean = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        int max = Math.max(1, Math.min(50, limit));
        if (clean.isEmpty()) {
            all.sort((a, b) -> {
                int pinned = Boolean.compare(b.isPinned(), a.isPinned());
                if (pinned != 0) return pinned;
                return Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
            });
            return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        }
        final String queryText = clean;
        final List<String> queryTokens = LexicalItemMatcher.tokenize(clean);
        List<Scored> scored = new ArrayList<>();
        for (Lesson lesson : all) {
            double score = score(lesson, queryText, queryTokens);
            if (score > 0) scored.add(new Scored(lesson, score));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Long.compare(b.entry.getUpdatedAt(), a.entry.getUpdatedAt());
        });
        List<Lesson> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < max; i++) out.add(scored.get(i).entry);
        return out;
    }

    public synchronized List<Lesson> list(String scope, int limit) {
        return search("", scope, limit);
    }

    /**
     * Lessons injected into a task: relevant matches first, then pinned
     * entries (usually hard safety rules), capped.
     */
    public synchronized List<Lesson> forContext(String query, int limit) {
        int max = Math.max(1, Math.min(20, limit));
        List<Lesson> out = new ArrayList<>(search(query, null, max));
        for (Lesson lesson : this.entries.values()) {
            if (!lesson.isPinned() || out.contains(lesson)) continue;
            out.add(lesson);
            if (out.size() >= max) break;
        }
        return out;
    }

    public synchronized void recordUse(String idOrTitle) {
        Lesson lesson = find(idOrTitle);
        if (lesson == null) return;
        lesson.setUses(lesson.getUses() + 1);
        lesson.touch();
        save();
    }

    public synchronized boolean forget(String idOrTitle) {
        if (idOrTitle == null || idOrTitle.trim().isEmpty()) return false;
        String key = idOrTitle.trim();
        if (this.entries.remove(key) != null) {
            save();
            return true;
        }
        String title = normalizeTitle(key);
        List<String> remove = new ArrayList<>();
        for (Lesson lesson : this.entries.values()) {
            if (lesson.getTitle().equals(title)) remove.add(lesson.getId());
        }
        for (String id : remove) this.entries.remove(id);
        if (!remove.isEmpty()) save();
        return !remove.isEmpty();
    }

    public synchronized int count() {
        return this.entries.size();
    }

    public synchronized String formatForPrompt(List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Lesson lesson : lessons) {
            out.append("- [").append(lesson.getScope()).append('/').append(lesson.getOutcome()).append("] ")
                    .append(lesson.getTitle());
            if (lesson.isPinned()) out.append(" (pinned)");
            out.append(" — ").append(lesson.summary(220)).append('\n');
        }
        return out.toString();
    }

    public File storageFile() {
        return this.storageFile;
    }

    private Lesson find(String idOrTitle) {
        if (idOrTitle == null) return null;
        Lesson direct = this.entries.get(idOrTitle.trim());
        if (direct != null) return direct;
        String title = normalizeTitle(idOrTitle);
        for (Lesson lesson : this.entries.values()) {
            if (lesson.getTitle().equals(title)) return lesson;
        }
        return null;
    }

    private List<Lesson> filtered(String scope) {
        List<Lesson> out = new ArrayList<>();
        String wanted = scope != null ? scope.trim().toLowerCase(Locale.ROOT) : "";
        for (Lesson lesson : this.entries.values()) {
            if (wanted.isEmpty() || wanted.equals("all") || wanted.equals(lesson.getScope())) out.add(lesson);
        }
        return out;
    }

    private double score(Lesson lesson, String queryText, List<String> queryTokens) {
        double score = lesson.isPinned() ? 4.0D : 0.0D;
        score += Math.min(2.0D, lesson.getUses() * 0.2D);
        String title = lesson.getTitle() != null ? lesson.getTitle() : "";
        String body = lesson.getBody() != null ? lesson.getBody() : "";
        String haystack = (title + " "
                + String.join(" ", lesson.getTags() != null ? lesson.getTags() : Collections.emptyList())
                + " " + body + " " + lesson.getScope()).toLowerCase(Locale.ROOT);
        if (title.equals(queryText)) score += 8.0D;
        if (title.contains(queryText)) score += 4.0D;
        if (haystack.contains(queryText)) score += 2.0D;
        for (String token : queryTokens) {
            if (token.length() < 2) continue;
            if (title.contains(token)) score += 3.0D;
            else if (haystack.contains(token)) score += 1.5D;
            else {
                for (String hayToken : LexicalItemMatcher.tokenize(haystack)) {
                    if (LexicalItemMatcher.fuzzyTokenMatches(hayToken, token)) {
                        score += 0.75D;
                        break;
                    }
                }
            }
        }
        return score;
    }

    private Lesson findByScopeAndTitle(String scope, String title) {
        for (Lesson lesson : this.entries.values()) {
            if (lesson.getScope().equals(scope) && lesson.getTitle().equals(title)) return lesson;
        }
        return null;
    }

    private void enforceLimit() {
        while (this.entries.size() > MAX_ENTRIES) {
            String oldestId = null;
            long oldest = Long.MAX_VALUE;
            for (Lesson lesson : this.entries.values()) {
                if (lesson.isPinned()) continue;
                if (lesson.getUpdatedAt() < oldest) {
                    oldest = lesson.getUpdatedAt();
                    oldestId = lesson.getId();
                }
            }
            if (oldestId == null) break;
            this.entries.remove(oldestId);
        }
    }

    private synchronized void load() {
        if (this.storageFile == null || !this.storageFile.isFile()) return;
        try (Reader reader = new FileReader(this.storageFile)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonArray()) return;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                Lesson lesson = CortexDroneMod.GSON.fromJson(element, Lesson.class);
                if (lesson == null || lesson.getId() == null || lesson.getId().isEmpty()) continue;
                if (lesson.getTags() == null) lesson.setTags(new ArrayList<>());
                if (lesson.getScope() == null) lesson.setScope("general");
                if (lesson.getTitle() == null) lesson.setTitle("");
                if (lesson.getBody() == null) lesson.setBody("");
                if (lesson.getOutcome() == null) lesson.setOutcome("note");
                if (lesson.getSource() == null) lesson.setSource("agent");
                if (lesson.getVersion() <= 0) lesson.setVersion(1);
                long now = System.currentTimeMillis();
                if (lesson.getCreatedAt() <= 0) lesson.setCreatedAt(now);
                if (lesson.getUpdatedAt() <= 0) lesson.setUpdatedAt(now);
                this.entries.put(lesson.getId(), lesson);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read lessons " + this.storageFile.getAbsolutePath(), e);
        }
    }

    private synchronized void save() {
        if (this.storageFile == null) return;
        File parent = this.storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOG.warning("Could not create lesson directory " + parent.getAbsolutePath());
            return;
        }
        File temp = new File(this.storageFile.getAbsolutePath() + ".tmp");
        try (Writer writer = new FileWriter(temp)) {
            CortexDroneMod.GSON.toJson(new ArrayList<>(this.entries.values()), writer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not write lessons", e);
            return;
        }
        if (this.storageFile.exists() && !this.storageFile.delete()) {
            LOG.warning("Could not replace " + this.storageFile.getAbsolutePath());
        }
        if (!temp.renameTo(this.storageFile)) {
            LOG.warning("Could not move " + temp.getAbsolutePath() + " into place");
        }
    }

    private static String normalizeScope(String value) {
        if (value == null || value.trim().isEmpty()) return "general";
        String scope = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (scope.length() > 24) scope = scope.substring(0, 24).trim();
        return scope.isEmpty() ? "general" : scope;
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        String title = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        if (title.length() > 80) title = title.substring(0, 80).trim();
        return title;
    }

    private static String normalizeOutcome(String value) {
        if (value == null) return "note";
        String clean = value.trim().toLowerCase(Locale.ROOT);
        if (clean.equals("success") || clean.equals("failure") || clean.equals("note")) return clean;
        return "note";
    }

    private static List<String> limitTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) continue;
            String value = tag.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !out.contains(value)) out.add(value);
            if (out.size() >= 16) break;
        }
        return out;
    }

    private static final class Scored {
        final Lesson entry;
        final double score;

        Scored(Lesson entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
