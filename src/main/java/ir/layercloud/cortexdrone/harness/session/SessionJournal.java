package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable session journal: one file per agent task, grouped by outcome.
 *
 * <p>Every {@code @drone} task creates a {@link SessionRun}. When the loop ends
 * the run is written to {@code config/cortexdrone/sessions/<outcome>/} as both
 * a machine-readable JSON transcript and a readable Markdown transcript. A
 * single compact line is appended to {@code sessions/index.jsonl} so
 * {@code /drone sessions} can list and count runs without opening every file.</p>
 *
 * <p>Outcome categories are {@code success}, {@code failure}, {@code cancelled},
 * {@code interrupted}, {@code incomplete} and {@code error}. A dropped run
 * (replaced task, despawn, shutdown, cancel) is still written instead of being
 * lost.</p>
 */
public final class SessionJournal {
    private static final int SCHEMA = 1;
    private static final int MAX_TRANSCRIPT_ENTRIES = 500;
    private static final int MAX_ENTRY_CHARS = 6_000;
    private static final Gson COMPACT = new Gson();

    private final File sessionsRoot;
    private final File indexFile;
    private final DroneLogger logger;
    private final Map<String, SessionRun> active = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public SessionJournal(File configRoot, DroneLogger logger) {
        File root = configRoot != null ? configRoot : new File("config/cortexdrone");
        this.sessionsRoot = new File(root, "sessions");
        this.indexFile = new File(this.sessionsRoot, "index.jsonl");
        this.logger = logger;
        ensureDirectory(this.sessionsRoot);
    }

    /**
     * Open a run at the start of an agent loop. The returned object is
     * thread-safe and must be finished exactly once.
     */
    public SessionRun begin(int loopId, String droneName, int droneId, String ownerName,
                            String playerName, String intent, String model,
                            int dimension, double x, double y, double z, String prompt) {
        SessionRun run = new SessionRun(this, loopId, droneName, droneId, ownerName,
                playerName, intent, model, dimension, x, y, z, prompt);
        this.active.put(run.id, run);
        return run;
    }

    /** Called by the run itself under its own lock. */
    private void finished(SessionRun run) {
        this.active.remove(run.id);
    }

    /**
     * Writes every run that is still open as interrupted. Called on server stop
     * and config reload so a shutdown never loses an in-flight transcript.
     */
    public void closeActive(SessionOutcome outcome, String reason) {
        SessionOutcome resolved = outcome != null ? outcome : SessionOutcome.INTERRUPTED;
        for (SessionRun run : new ArrayList<>(this.active.values())) {
            run.finish(resolved, reason != null ? reason : "closed", null);
        }
    }

    public int activeCount() {
        return this.active.size();
    }

    /** Recent index entries, newest first, optionally filtered by outcome. */
    public List<JsonObject> recent(int limit, SessionOutcome filter) {
        List<JsonObject> all = readIndex();
        List<JsonObject> out = new ArrayList<>();
        int max = Math.max(1, Math.min(200, limit));
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            JsonObject entry = all.get(i);
            if (filter != null) {
                String outcome = entry.has("outcome") ? entry.get("outcome").getAsString() : "";
                if (!filter.id().equals(outcome)) continue;
            }
            out.add(entry);
        }
        return out;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SessionOutcome outcome : SessionOutcome.values()) counts.put(outcome.id(), 0);
        for (JsonObject entry : readIndex()) {
            String outcome = entry.has("outcome") ? entry.get("outcome").getAsString() : "unknown";
            Integer current = counts.get(outcome);
            counts.put(outcome, current != null ? current + 1 : 1);
        }
        return counts;
    }

    /** Find an index entry by full id, id prefix or file path fragment. */
    public JsonObject find(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        String needle = key.trim();
        List<JsonObject> all = readIndex();
        for (int i = all.size() - 1; i >= 0; i--) {
            JsonObject entry = all.get(i);
            String id = entry.has("id") ? entry.get("id").getAsString() : "";
            String file = entry.has("file") ? entry.get("file").getAsString() : "";
            if (id.equals(needle) || id.startsWith(needle) || file.equals(needle)
                    || file.contains(needle)) {
                return entry;
            }
        }
        return null;
    }

    public File sessionsRoot() {
        return this.sessionsRoot;
    }

    public File fileFor(JsonObject entry) {
        if (entry == null || !entry.has("file")) return null;
        return new File(this.sessionsRoot, entry.get("file").getAsString());
    }

    private void write(SessionRun run, SessionOutcome outcome, String reason) {
        String topic = classifyTopic(run.prompt, run.transcript);
        JsonObject json = buildJson(run, outcome, reason, topic);
        String baseName = run.fileStem + "-" + topic + "-" + run.fileSuffix;
        File directory = new File(this.sessionsRoot, outcome.directory());
        if (!ensureDirectory(directory)) {
            logLine(run, outcome, reason, topic, "<directory unavailable>");
            return;
        }
        File jsonFile = uniqueFile(directory, baseName, ".json");
        File markdownFile = new File(jsonFile.getAbsolutePath().replaceFirst("\\.json$", ".md"));

        try (Writer writer = new FileWriter(jsonFile)) {
            CortexDroneMod.GSON.toJson(json, writer);
        } catch (Exception e) {
            logLine(run, outcome, reason, topic, "<write failed: " + e + ">");
            return;
        }
        writeMarkdown(markdownFile, run, outcome, reason, topic);

        JsonObject index = new JsonObject();
        index.addProperty("schema", SCHEMA);
        index.addProperty("id", run.id);
        index.addProperty("outcome", outcome.id());
        index.addProperty("topic", topic);
        index.addProperty("reason", reason != null ? reason : "");
        index.addProperty("at", run.startedIso);
        index.addProperty("at_ms", run.startedAt);
        index.addProperty("duration_ms", Math.max(0L, run.endedAt - run.startedAt));
        index.addProperty("loop_id", run.loopId);
        index.addProperty("drone", run.droneName);
        index.addProperty("owner", run.ownerName);
        index.addProperty("player", run.playerName);
        index.addProperty("prompt", snippet(run.prompt, 240));
        index.addProperty("turns", run.turns);
        index.addProperty("tool_calls", run.toolCalls);
        index.addProperty("tool_errors", run.toolErrors);
        index.addProperty("world_actions", run.worldActions);
        index.addProperty("file", outcome.directory() + "/" + jsonFile.getName());
        appendIndex(index);

        logLine(run, outcome, reason, topic,
                outcome.directory() + "/" + jsonFile.getName());
    }

    private JsonObject buildJson(SessionRun run, SessionOutcome outcome, String reason, String topic) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("id", run.id);
        root.addProperty("outcome", outcome.id());
        root.addProperty("topic", topic);
        root.addProperty("reason", reason != null ? reason : "");
        root.addProperty("started_at", run.startedIso);
        root.addProperty("started_at_ms", run.startedAt);
        root.addProperty("ended_at", iso(run.endedAt));
        root.addProperty("ended_at_ms", run.endedAt);
        root.addProperty("duration_ms", Math.max(0L, run.endedAt - run.startedAt));

        root.addProperty("loop_id", run.loopId);
        JsonObject drone = new JsonObject();
        drone.addProperty("id", run.droneId);
        drone.addProperty("name", run.droneName);
        root.add("drone", drone);
        root.addProperty("owner", run.ownerName);
        root.addProperty("player", run.playerName);
        root.addProperty("intent", run.intent);
        root.addProperty("model", run.model);
        root.addProperty("dimension", run.dimension);
        JsonObject start = new JsonObject();
        start.addProperty("x", run.x);
        start.addProperty("y", run.y);
        start.addProperty("z", run.z);
        root.add("start", start);

        root.addProperty("prompt", run.prompt);
        root.addProperty("turns", run.turns);
        root.addProperty("tool_calls", run.toolCalls);
        root.addProperty("tool_errors", run.toolErrors);
        root.addProperty("world_actions", run.worldActions);
        root.addProperty("final_reply", run.finalReply);

        JsonArray transcript = new JsonArray();
        for (JsonObject entry : run.transcript) transcript.add(entry);
        root.add("transcript", transcript);
        return root;
    }

    private void writeMarkdown(File file, SessionRun run, SessionOutcome outcome,
                               String reason, String topic) {
        StringBuilder out = new StringBuilder(4_096);
        out.append("# Session ").append(run.id).append('\n').append('\n');
        out.append("- outcome: **").append(outcome.id()).append("**\n");
        out.append("- topic: ").append(topic).append('\n');
        out.append("- reason: ").append(reason != null ? reason : "").append('\n');
        out.append("- drone: #").append(run.droneId).append(' ').append(run.droneName).append('\n');
        out.append("- owner/player: ").append(run.ownerName)
                .append(" / ").append(run.playerName).append('\n');
        out.append("- intent: ").append(run.intent).append('\n');
        out.append("- model: ").append(run.model).append('\n');
        out.append("- dimension: ").append(run.dimension).append('\n');
        out.append("- start: (").append(fmt(run.x)).append(", ").append(fmt(run.y))
                .append(", ").append(fmt(run.z)).append(")\n");
        out.append("- started: ").append(run.startedIso).append('\n');
        out.append("- duration: ").append(Math.max(0L, run.endedAt - run.startedAt)).append(" ms\n");
        out.append("- turns/tools/errors: ").append(run.turns).append(" / ")
                .append(run.toolCalls).append(" / ").append(run.toolErrors).append('\n');
        out.append('\n').append("## Prompt\n\n").append(run.prompt).append('\n');

        if (!run.transcript.isEmpty()) {
            out.append('\n').append("## Transcript\n\n");
            for (JsonObject entry : run.transcript) {
                String type = string(entry, "type");
                int turn = entry.has("turn") ? entry.get("turn").getAsInt() : 0;
                if ("thought".equals(type)) {
                    out.append("### T").append(turn).append(" thought\n\n")
                            .append(string(entry, "text")).append("\n\n");
                } else if ("tool".equals(type)) {
                    boolean ok = entry.has("ok") && entry.get("ok").getAsBoolean();
                    out.append("### T").append(turn).append(" tool `")
                            .append(string(entry, "name")).append("` -> ")
                            .append(ok ? "ok" : "error").append("\n\n");
                    String args = string(entry, "args");
                    if (!args.isEmpty()) out.append("args: `").append(args).append("`\n\n");
                    String result = string(entry, "result");
                    if (!result.isEmpty()) out.append("```\n").append(result).append("\n```\n\n");
                    String error = string(entry, "error");
                    if (!error.isEmpty()) out.append("error: ").append(error).append("\n\n");
                } else if ("reply".equals(type)) {
                    out.append("### T").append(turn).append(" reply\n\n")
                            .append(string(entry, "text")).append("\n\n");
                }
            }
        }

        try (Writer writer = new FileWriter(file)) {
            writer.write(out.toString());
        } catch (Exception ignored) {
            // The JSON transcript is authoritative; a missing .md must not fail the run.
        }
    }

    private void appendIndex(JsonObject index) {
        ensureDirectory(this.sessionsRoot);
        String line = COMPACT.toJson(index);
        synchronized (this.indexFile) {
            try (FileWriter writer = new FileWriter(this.indexFile, true)) {
                writer.write(line);
                writer.write('\n');
            } catch (Exception ignored) {
                // A failed index append must not lose the session files themselves.
            }
        }
    }

    private List<JsonObject> readIndex() {
        List<JsonObject> entries = new ArrayList<>();
        if (!this.indexFile.isFile()) return entries;
        try (BufferedReader reader = new BufferedReader(new FileReader(this.indexFile))) {
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

    private void logLine(SessionRun run, SessionOutcome outcome, String reason,
                         String topic, String file) {
        if (this.logger == null) return;
        this.logger.log("SESSION", null,
                "Session " + outcome.id() + " (" + topic + ") drone=" + run.droneName
                        + " loop=" + run.loopId + " reason=" + (reason != null ? reason : ""),
                "file=" + file + " turns=" + run.turns + " tools=" + run.toolCalls
                        + " errors=" + run.toolErrors + " durationMs="
                        + Math.max(0L, run.endedAt - run.startedAt));
    }

    private static File uniqueFile(File directory, String baseName, String extension) {
        File candidate = new File(directory, baseName + extension);
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private static boolean ensureDirectory(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }

    private static String iso(long timestamp) {
        if (timestamp <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .format(new Date(timestamp));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static String snippet(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String cap(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_ENTRY_CHARS) return value;
        return value.substring(0, MAX_ENTRY_CHARS) + "...(+" + (value.length() - MAX_ENTRY_CHARS) + " chars)";
    }

    private static String classifyTopic(String prompt, List<JsonObject> transcript) {
        String text = prompt != null ? prompt.toLowerCase(Locale.ROOT) : "";
        if (containsAny(text, "mine", "mining", "dig", "digging", "ore", "diamond", "coal",
                "iron", "gold", "quarry", "excavat", "tunnel")) {
            return "mining";
        }
        if (containsAny(text, "build", "building", "place", "wall", "house", "tower",
                "platform", "bridge", "roof", "floor", "farm")) {
            return "building";
        }
        if (containsAny(text, "go to", "goto", "travel", "fly", "navigate", "return",
                "come to", "waypoint", "base", "home")) {
            return "navigation";
        }
        if (containsAny(text, "inventory", "chest", "item", "store", "deposit",
                "withdraw", "give", "take", "drop", "craft", "sort")) {
            return "inventory";
        }
        if (containsAny(text, "attack", "kill", "fight", "mob", "zombie", "skeleton",
                "creeper", "combat", "defend")) {
            return "combat";
        }
        if (containsAny(text, "survive", "escape", "safe", "emergency", "lava", "fire", "fall")) {
            return "survival";
        }
        // Fall back to the tool surface when the prompt is vague or non-Latin.
        String tools = transcript != null ? transcript.toString().toLowerCase(Locale.ROOT) : "";
        if (containsAny(tools, "goto", "breadcrumbs")) return "navigation";
        if (containsAny(tools, "break_block", "scan_blocks", "scan_veins")) return "mining";
        if (containsAny(tools, "place_block")) return "building";
        if (containsAny(tools, "craft", "deposit_chest", "withdraw_chest", "give_to_player",
                "take_from_player", "move_item")) {
            return "inventory";
        }
        return "general";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    /** One agent task from start to finish. */
    public static final class SessionRun {
        private final SessionJournal journal;
        private final String id;
        private final String fileStem;
        private final String fileSuffix;
        private final String startedIso;
        private final long startedAt;
        private final int loopId;
        private final String droneName;
        private final int droneId;
        private final String ownerName;
        private final String playerName;
        private final String intent;
        private final String model;
        private final int dimension;
        private final double x;
        private final double y;
        private final double z;
        private final String prompt;
        private final List<JsonObject> transcript = new ArrayList<>();

        private int turns;
        private int toolCalls;
        private int toolErrors;
        private int worldActions;
        private String finalReply = "";
        private String cancelReason;
        private long endedAt;
        private boolean finished;

        private SessionRun(SessionJournal journal, int loopId, String droneName, int droneId,
                           String ownerName, String playerName, String intent, String model,
                           int dimension, double x, double y, double z, String prompt) {
            this.journal = journal;
            this.loopId = loopId;
            this.droneName = droneName != null ? droneName : "unknown";
            this.droneId = droneId;
            this.ownerName = ownerName != null ? ownerName : "";
            this.playerName = playerName != null ? playerName : "";
            this.intent = intent != null ? intent : "UNKNOWN";
            this.model = model != null ? model : "";
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.prompt = prompt != null ? prompt : "";
            this.startedAt = System.currentTimeMillis();
            this.startedIso = iso(this.startedAt);
            long sequence = journal.sequence.incrementAndGet();
            int random = ThreadLocalRandom.current().nextInt(0x10000);
            this.id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                    .format(new Date(this.startedAt))
                    + "-d" + droneId + "-l" + loopId + "-" + sequence
                    + "-" + Integer.toHexString(random);
            this.fileStem = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                    .format(new Date(this.startedAt))
                    + "-d" + droneId + "-l" + loopId;
            this.fileSuffix = sequence + "-" + Integer.toHexString(random);
        }

        public String id() {
            return this.id;
        }

        /** True when every tool call in the session failed. */
        public synchronized boolean allToolsFailed() {
            return this.toolCalls > 0 && this.toolErrors >= this.toolCalls;
        }

        public synchronized void thought(int turn, String text) {
            if (this.finished || text == null || text.trim().isEmpty()) return;
            this.turns = Math.max(this.turns, turn);
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "thought");
            entry.addProperty("turn", turn);
            entry.addProperty("text", cap(text.trim()));
            append(entry);
        }

        public synchronized void tool(int turn, String name, String args, boolean ok, String result) {
            if (this.finished) return;
            this.turns = Math.max(this.turns, turn);
            this.toolCalls++;
            if (!ok) this.toolErrors++;
            if (ok && isWorldAction(name)) this.worldActions++;
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "tool");
            entry.addProperty("turn", turn);
            entry.addProperty("name", name != null ? name : "");
            entry.addProperty("ok", ok);
            entry.addProperty("args", cap(args));
            entry.addProperty("result", cap(result));
            append(entry);
        }

        public synchronized void toolError(int turn, String name, String args, String error) {
            if (this.finished) return;
            this.turns = Math.max(this.turns, turn);
            this.toolCalls++;
            this.toolErrors++;
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "tool");
            entry.addProperty("turn", turn);
            entry.addProperty("name", name != null ? name : "");
            entry.addProperty("ok", false);
            entry.addProperty("args", cap(args));
            entry.addProperty("error", cap(error));
            append(entry);
        }

        public synchronized void reply(String text) {
            if (this.finished || text == null || text.trim().isEmpty()) return;
            this.finalReply = cap(text.trim());
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "reply");
            entry.addProperty("turn", this.turns);
            entry.addProperty("text", this.finalReply);
            append(entry);
        }

        /** Marks that the model itself asked to cancel the task. */
        public synchronized void cancelRequested(String reason) {
            if (this.finished) return;
            this.cancelReason = reason != null ? reason : "model_cancel";
        }

        /**
         * Writes the session once. Safe to call from the agent loop and the
         * shutdown hook; the second call is ignored.
         */
        public void finish(SessionOutcome outcome, String reason, String reply) {
            synchronized (this) {
                if (this.finished) return;
                this.finished = true;
                if (reply != null && !reply.trim().isEmpty()) {
                    String clean = cap(reply.trim());
                    if (!clean.equals(this.finalReply)) {
                        this.finalReply = clean;
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", "reply");
                        entry.addProperty("turn", this.turns);
                        entry.addProperty("text", clean);
                        append(entry);
                    }
                }
                this.endedAt = System.currentTimeMillis();
                SessionOutcome resolved = resolve(outcome);
                this.journal.write(this, resolved, reason);
                this.journal.finished(this);
            }
        }

        private SessionOutcome resolve(SessionOutcome outcome) {
            if (this.cancelReason != null && outcome != SessionOutcome.ERROR) {
                if ("model_cancel".equals(this.cancelReason) || "player_cancel".equals(this.cancelReason)) {
                    return SessionOutcome.CANCELLED;
                }
                return SessionOutcome.INTERRUPTED;
            }
            return outcome != null ? outcome : SessionOutcome.INCOMPLETE;
        }

        private void append(JsonObject entry) {
            if (this.transcript.size() >= MAX_TRANSCRIPT_ENTRIES) return;
            this.transcript.add(entry);
        }

        private static boolean isWorldAction(String name) {
            if (name == null) return false;
            return name.equals("goto") || name.equals("break_block") || name.equals("place_block")
                    || name.equals("interact_block") || name.equals("use_item")
                    || name.equals("attack_entity") || name.equals("pick_entity")
                    || name.equals("craft") || name.equals("deposit_chest")
                    || name.equals("withdraw_chest") || name.equals("give_to_player")
                    || name.equals("take_from_player") || name.equals("move_item")
                    || name.equals("drop_items") || name.equals("execute");
        }
    }
}
