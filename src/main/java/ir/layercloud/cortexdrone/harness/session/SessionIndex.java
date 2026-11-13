package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The append-only {@code index.jsonl} beside the session transcripts.
 *
 * <p>Extracted from {@link SessionJournal}. The index is a cache of what is on
 * disk: a torn or missing line is skipped, never repaired destructively, because
 * the per-session JSON files are the source of truth.</p>
 */
final class SessionIndex {
    private static final int MAX_HISTORY = 200;
    /**
     * Lines kept in {@code index.jsonl}. Older lines are dropped from the cache
     * file; their session JSON/Markdown transcripts remain on disk.
     */
    static final int MAX_LINES = 5000;
    /**
     * Appends between trim checks; trimming re-reads the file, so it must not
     * run on every finished task.
     */
    static final int TRIM_CHECK_INTERVAL = 64;
    private static final Gson COMPACT = new Gson();

    private final File indexFile;
    private int appendsSinceTrim;

    SessionIndex(File indexFile) {
        this.indexFile = indexFile;
    }

    /**
     * Appends one entry as a single line. Failures are silent by design.
     */
    synchronized void append(JsonObject entry) {
        if (entry == null) return;
        File parent = this.indexFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(this.indexFile, true),
                java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(COMPACT.toJson(entry));
            writer.write('\n');
        } catch (Exception ignored) {
            // A failed index append must not lose the session files themselves.
        }
        trimIfNeeded();
    }

    /**
     * Keeps the append-only index bounded.
     *
     * <p>The index is a cache: the per-session JSON files are authoritative, so
     * dropping the oldest lines is safe. It used to grow without limit (one line
     * per task, forever) and every {@code /drone sessions list} loaded the whole
     * file.</p>
     */
    private void trimIfNeeded() {
        if (!this.indexFile.isFile()) return;
        this.appendsSinceTrim++;
        if (this.appendsSinceTrim < TRIM_CHECK_INTERVAL) return;
        this.appendsSinceTrim = 0;
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(this.indexFile),
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (Exception ignored) {
            return;
        }
        if (lines.size() <= MAX_LINES) return;
        List<String> kept = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
        StringBuilder out = new StringBuilder(kept.size() * 160);
        for (String line : kept) out.append(line).append('\n');
        AtomicFiles.writeText(this.indexFile, out.toString());
    }

    /**
     * Every readable entry, oldest first.
     */
    synchronized List<JsonObject> read() {
        List<JsonObject> entries = new ArrayList<>();
        if (!this.indexFile.isFile()) return entries;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(this.indexFile),
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.trim();
                if (clean.isEmpty()) continue;
                try {
                    JsonElement parsed = new JsonParser().parse(clean);
                    if (parsed != null && parsed.isJsonObject()) {
                        entries.add(parsed.getAsJsonObject());
                    }
                } catch (Exception ignored) {
                    // Skip a torn line rather than hiding the rest of the history.
                }
            }
        } catch (Exception ignored) {
            // No history is a valid state.
        }
        return entries;
    }

    /**
     * Most recent entries first, optionally filtered, capped at {@code limit}.
     */
    List<JsonObject> recent(int limit, SessionOutcome filter) {
        List<JsonObject> all = read();
        List<JsonObject> out = new ArrayList<>();
        int max = Math.max(1, Math.min(MAX_HISTORY, limit));
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            JsonObject entry = all.get(i);
            if (filter == null || filter.id().equals(outcomeOf(entry))) out.add(entry);
        }
        return out;
    }

    /**
     * One counter per known outcome, in enum order.
     */
    Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SessionOutcome outcome : SessionOutcome.values()) counts.put(outcome.id(), 0);
        for (JsonObject entry : read()) {
            String outcome = outcomeOf(entry);
            Integer current = counts.get(outcome);
            counts.put(outcome, current != null ? current + 1 : 1);
        }
        return counts;
    }

    /**
     * Finds an entry by full id, id prefix or file path fragment.
     */
    JsonObject find(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        String needle = key.trim();
        List<JsonObject> all = read();
        for (int i = all.size() - 1; i >= 0; i--) {
            JsonObject entry = all.get(i);
            String id = string(entry, "id");
            String file = string(entry, "file");
            if (id.equals(needle) || id.startsWith(needle) || file.equals(needle)
                    || file.contains(needle)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Resolves the transcript file of one index entry.
     */
    File fileFor(File sessionsRoot, JsonObject entry) {
        String file = string(entry, "file");
        return file.isEmpty() ? null : new File(sessionsRoot, file);
    }

    private static String outcomeOf(JsonObject entry) {
        String outcome = string(entry, "outcome");
        return outcome.isEmpty() ? "unknown" : outcome;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }
}
