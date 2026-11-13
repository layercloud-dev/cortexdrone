package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ConfigDirectory;
import ir.layercloud.cortexdrone.config.DroneLogger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

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
@SuppressWarnings("unused")
public final class SessionJournal {
    private static final int MAX_TRANSCRIPT_ENTRIES = 500;
    private static final int MAX_ENTRY_CHARS = 6_000;

    private final File sessionsRoot;
    private final File indexFile;
    private final DroneLogger logger;
    private final Map<String, SessionRun> active = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final SessionIndex index;
    private final SessionWriter writer;
    /**
     * Decides whether a tool call counts as a world action. Wired from the tool
     * registry so a new mutating tool is counted without editing a list here.
     */
    private Predicate<String> worldAction = name -> false;

    public SessionJournal(File configRoot, DroneLogger logger) {
        File root = configRoot != null ? configRoot : new File(ConfigDirectory.resolve(), "cortexdrone");
        this.sessionsRoot = new File(root, "sessions");
        this.indexFile = new File(this.sessionsRoot, "index.jsonl");
        this.index = new SessionIndex(this.indexFile);
        this.writer = new SessionWriter(this.sessionsRoot, this.index, logger);
        this.logger = logger;
        ensureDirectory(this.sessionsRoot);
    }

    /**
     * Installs the capability-driven world-action predicate.
     */
    public void setWorldActionPredicate(Predicate<String> predicate) {
        if (predicate != null) this.worldAction = predicate;
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

    /**
     * Called by the run itself under its own lock.
     */
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

    /**
     * Recent index entries, newest first, optionally filtered by outcome.
     */
    public List<JsonObject> recent(int limit, SessionOutcome filter) {
        return this.index.recent(limit, filter);
    }

    public Map<String, Integer> counts() {
        return this.index.counts();
    }

    /**
     * Find an index entry by full id, id prefix or file path fragment.
     */
    public JsonObject find(String key) {
        return this.index.find(key);
    }

    public File sessionsRoot() {
        return this.sessionsRoot;
    }

    public File fileFor(JsonObject entry) {
        return this.index.fileFor(this.sessionsRoot, entry);
    }

    private void write(SessionRun run, SessionOutcome outcome, String reason) {
        this.writer.write(run, outcome, reason, classifyTopic(run.prompt, run.transcript));
    }

    private static boolean ensureDirectory(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }

    private static String cap(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_ENTRY_CHARS) return value;
        return value.substring(0, MAX_ENTRY_CHARS) + "...(+" + (value.length() - MAX_ENTRY_CHARS) + " chars)";
    }

    private static String classifyTopic(String prompt, List<JsonObject> transcript) {
        return SessionTopics.classify(prompt, transcript);
    }

    /**
     * One agent task from start to finish.
     */
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
        private final Predicate<String> worldActionRule;
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
            this.worldActionRule = journal.worldAction;
            this.startedAt = System.currentTimeMillis();
            this.startedIso = SessionJson.iso(this.startedAt);
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

        String fileStem() {
            return this.fileStem;
        }

        String fileSuffix() {
            return this.fileSuffix;
        }

        String droneName() {
            return this.droneName;
        }

        int loopId() {
            return this.loopId;
        }

        String finalReply() {
            return this.finalReply;
        }

        int turns() {
            return this.turns;
        }

        int toolCalls() {
            return this.toolCalls;
        }

        int toolErrors() {
            return this.toolErrors;
        }

        long durationMs() {
            return Math.max(0L, this.endedAt - this.startedAt);
        }

        /**
         * True when every tool call in the session failed.
         */
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

        /**
         * Marks that the model itself asked to cancel the task.
         */
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

        /**
         * Copies the fields the Markdown view needs.
         *
         * <p>Taken under the run's lock so rendering never sees a counter update
         * halfway through.</p>
         */
        synchronized SessionMarkdown.Snapshot markdownSnapshot() {
            SessionMarkdown.Snapshot snapshot = new SessionMarkdown.Snapshot();
            snapshot.id = this.id;
            snapshot.droneName = this.droneName;
            snapshot.droneId = this.droneId;
            snapshot.loopId = this.loopId;
            snapshot.ownerName = this.ownerName;
            snapshot.playerName = this.playerName;
            snapshot.intent = this.intent;
            snapshot.model = this.model;
            snapshot.dimension = this.dimension;
            snapshot.x = this.x;
            snapshot.y = this.y;
            snapshot.z = this.z;
            snapshot.startedIso = this.startedIso;
            snapshot.startedAt = this.startedAt;
            snapshot.endedAt = this.endedAt;
            snapshot.turns = this.turns;
            snapshot.toolCalls = this.toolCalls;
            snapshot.toolErrors = this.toolErrors;
            snapshot.worldActions = this.worldActions;
            snapshot.prompt = this.prompt;
            snapshot.transcript = new ArrayList<>(this.transcript);
            return snapshot;
        }

        /**
         * True when the tool changes the world or an inventory.
         *
         * <p>The declaration on the {@code ToolSpec} is authoritative; this run
         * captures the predicate when it is created.</p>
         */
        private boolean isWorldAction(String name) {
            return this.worldActionRule != null && this.worldActionRule.test(name);
        }
    }
}
