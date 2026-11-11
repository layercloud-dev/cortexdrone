package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The JSON field vocabulary for a session record.
 *
 * <p>Extracted from {@code SessionJournal}. The transcript file and the index
 * line describe the same run, and used to spell the same fourteen property names
 * out twice — which is how {@code schema} and {@code world_actions} drifted apart
 * between them. Both now come from here.</p>
 */
final class SessionJson {
    /**
     * Bumped when the on-disk shape changes incompatibly.
     */
    static final int SCHEMA = 1;
    /**
     * Prompt length kept in the index line.
     */
    private static final int INDEX_PROMPT_CHARS = 240;

    private SessionJson() {
    }

    /**
     * The full per-session transcript.
     */
    static JsonObject transcript(SessionMarkdown.Snapshot run, SessionOutcome outcome,
                                 String reason, String topic, String finalReply) {
        JsonObject root = new JsonObject();
        common(root, run, outcome, reason, topic);
        root.addProperty("started_at", run.startedIso);
        root.addProperty("started_at_ms", run.startedAt);
        root.addProperty("ended_at", iso(run.endedAt));
        root.addProperty("ended_at_ms", run.endedAt);

        JsonObject drone = new JsonObject();
        drone.addProperty("id", run.droneId);
        drone.addProperty("name", run.droneName);
        root.add("drone", drone);
        root.addProperty("intent", run.intent);
        root.addProperty("model", run.model);
        root.addProperty("dimension", run.dimension);
        JsonObject start = new JsonObject();
        start.addProperty("x", run.x);
        start.addProperty("y", run.y);
        start.addProperty("z", run.z);
        root.add("start", start);
        root.addProperty("prompt", run.prompt);
        root.addProperty("final_reply", finalReply);

        JsonArray entries = new JsonArray();
        for (JsonObject entry : run.transcript) entries.add(entry);
        root.add("transcript", entries);
        return root;
    }

    /**
     * The one-line index entry for a finished run.
     */
    static JsonObject index(SessionMarkdown.Snapshot run, SessionOutcome outcome,
                            String reason, String topic, String relativeFile) {
        JsonObject entry = new JsonObject();
        common(entry, run, outcome, reason, topic);
        entry.addProperty("at", run.startedIso);
        entry.addProperty("at_ms", run.startedAt);
        entry.addProperty("loop_id", run.loopId);
        entry.addProperty("drone", run.droneName);
        entry.addProperty("owner", run.ownerName);
        entry.addProperty("player", run.playerName);
        entry.addProperty("prompt", snippet(run.prompt, INDEX_PROMPT_CHARS));
        entry.addProperty("file", relativeFile);
        return entry;
    }

    /**
     * Fields both shapes carry, so they cannot drift.
     */
    private static void common(JsonObject target, SessionMarkdown.Snapshot run,
                               SessionOutcome outcome, String reason, String topic) {
        target.addProperty("schema", SCHEMA);
        target.addProperty("id", run.id);
        target.addProperty("outcome", outcome.id());
        target.addProperty("topic", topic);
        target.addProperty("reason", reason != null ? reason : "");
        target.addProperty("duration_ms", Math.max(0L, run.endedAt - run.startedAt));
        target.addProperty("turns", run.turns);
        target.addProperty("tool_calls", run.toolCalls);
        target.addProperty("tool_errors", run.toolErrors);
        target.addProperty("world_actions", run.worldActions);
    }

    /**
     * ISO-8601 timestamp, or an empty string for "never".
     */
    static String iso(long timestamp) {
        if (timestamp <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .format(new Date(timestamp));
    }

    /**
     * Collapses whitespace and truncates with an ellipsis.
     */
    static String snippet(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }
}
