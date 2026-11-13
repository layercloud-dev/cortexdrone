package ir.layercloud.cortexdrone.harness.lesson;

import ir.layercloud.cortexdrone.config.ModPaths;
import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;
import ir.layercloud.cortexdrone.harness.markdown.WriteStatus;
import ir.layercloud.cortexdrone.service.search.RankedSearch;
import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persistent lessons: what went wrong, why, and the rule for next time.
 *
 * <p>Storage mirrors skills: one directory per lesson with a
 * {@code LESSON.md} that has YAML frontmatter and a prose body.</p>
 *
 * <pre>
 * config/cortexdrone/lessons/
 *   mining-keep-floor-1a2b3c4d/
 *     LESSON.md
 * </pre>
 *
 * <p>Unlike a skill (executable code), a lesson is short prose the model reads
 * before acting. Entries are deduplicated by {@code scope + title}, so repeating
 * the same mistake updates the existing lesson instead of duplicating it. Only
 * the directory format is supported; the retired {@code lessons.json} is not
 * read.</p>
 */
@SuppressWarnings("unused")
public final class LessonStore {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_ENTRIES = 400;

    private final File lessonsDir;
    private final LessonFiles files;
    private final Map<String, Lesson> entries = new LinkedHashMap<>();
    private long sequence;

    public LessonStore(File configRoot) {
        this.lessonsDir = ModPaths.lessonsDir(configRoot);
        this.files = new LessonFiles(this.lessonsDir);
        loadDirectories();
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
        if (!persist(lesson)) {
            // Roll back so a failed write is not reported as a stored lesson.
            if (lesson.getVersion() > 1) {
                lesson.setVersion(lesson.getVersion() - 1);
            } else {
                this.entries.remove(lesson.getId());
            }
            return null;
        }
        return lesson;
    }

    public synchronized List<Lesson> search(String query, String scope, int limit) {
        return RankedSearch.search(filtered(scope), query, limit, Lesson::isPinned,
                Lesson::getUpdatedAt, this::score);
    }

    public synchronized List<Lesson> list(String scope, int limit) {
        return search("", scope, limit);
    }

    /**
     * Lessons injected into a task: relevant matches first, then pinned
     * entries (usually hard safety rules), capped.
     */
    public synchronized List<Lesson> forContext(String query, int limit) {
        int max = RankedSearch.clampContext(limit);
        List<Lesson> out = new ArrayList<>(search(query, null, max));
        if (out.size() >= max) return out;
        for (Lesson lesson : this.entries.values()) {
            if (out.size() >= max) break;
            if (!lesson.isPinned() || out.contains(lesson)) continue;
            out.add(lesson);
        }
        return out;
    }

    public synchronized void recordUse(String idOrTitle) {
        Lesson lesson = find(idOrTitle);
        if (lesson == null) return;
        lesson.setUses(lesson.getUses() + 1);
        lesson.touch();
        persist(lesson);
    }

    public synchronized boolean forget(String idOrTitle) {
        if (idOrTitle == null || idOrTitle.trim().isEmpty()) return false;
        String key = idOrTitle.trim();
        Lesson direct = this.entries.remove(key);
        if (direct != null) {
            if (deleteLesson(direct)) return true;
            // The directory is still there; keep the in-memory entry truthful.
            this.entries.put(direct.getId(), direct);
            return false;
        }
        String title = normalizeTitle(key);
        List<Lesson> removed = new ArrayList<>();
        for (Lesson lesson : this.entries.values()) {
            if (lesson.getTitle().equals(title)) removed.add(lesson);
        }
        boolean allDeleted = true;
        for (Lesson lesson : removed) {
            this.entries.remove(lesson.getId());
            if (!deleteLesson(lesson)) {
                this.entries.put(lesson.getId(), lesson);
                allDeleted = false;
            }
        }
        return !removed.isEmpty() && allDeleted;
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

    public File lessonsDir() {
        return this.lessonsDir;
    }

    // ------------------------------------------------------------------
    // Loading and persistence
    // ------------------------------------------------------------------

    private synchronized void loadDirectories() {
        for (File dir : this.files.directories()) loadLesson(dir);
    }

    private void loadLesson(File dir) {
        File markdown = new File(dir, LessonMarkdown.FILE_NAME);
        if (!markdown.isFile()) return;
        Lesson lesson = LessonMarkdown.read(AtomicFiles
                .readText(markdown));
        if (lesson == null) return;
        long stamp = markdown.lastModified();
        if (lesson.getId() == null || lesson.getId().trim().isEmpty()) {
            lesson.setId("les_" + stamp + "_" + Integer.toUnsignedString(lesson.getTitle().hashCode()));
        }
        if (lesson.getScope() == null || lesson.getScope().trim().isEmpty()) lesson.setScope("general");
        if (lesson.getTitle() == null) lesson.setTitle("");
        if (lesson.getBody() == null) lesson.setBody("");
        if (lesson.getOutcome() == null) lesson.setOutcome("note");
        if (lesson.getSource() == null || lesson.getSource().trim().isEmpty()) lesson.setSource("agent");
        if (lesson.getTags() == null) lesson.setTags(new ArrayList<>());
        if (lesson.getVersion() <= 0) lesson.setVersion(1);
        if (lesson.getCreatedAt() <= 0) lesson.setCreatedAt(stamp);
        if (lesson.getUpdatedAt() <= 0) lesson.setUpdatedAt(stamp);
        lesson.setPath(dir.getAbsolutePath());
        this.entries.put(lesson.getId(), lesson);
    }

    private boolean persist(Lesson lesson) {
        if (this.files.persist(lesson)) return true;
        LOG.warning("Could not persist lesson " + lesson.getTitle());
        WriteStatus.failed(WriteStatus.reason("lesson '" + lesson.getTitle() + "'",
                lesson.getPath() != null ? lesson.getPath() : lessonsDir.getAbsolutePath()));
        return false;
    }

    private boolean deleteLesson(Lesson lesson) {
        if (this.files.delete(lesson)) return true;
        LOG.warning("Could not delete lesson " + (lesson != null ? lesson.getTitle() : "?"));
        WriteStatus.failed(WriteStatus.reason("lesson '" + (lesson != null ? lesson.getTitle() : "?") + "'",
                lesson != null && lesson.getPath() != null
                        ? lesson.getPath() : lessonsDir.getAbsolutePath()));
        return false;
    }


    private static String slug(String value, String fallback) {
        return LessonFiles.slug(value, fallback);
    }

    // ------------------------------------------------------------------
    // Query helpers
    // ------------------------------------------------------------------

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
            Lesson removed = this.entries.remove(oldestId);
            deleteLesson(removed);
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


}
