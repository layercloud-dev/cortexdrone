package ir.layercloud.cortexdrone.harness.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harness-side session state for one drone.
 *
 * <p>It owns the single task (one task, no instruction queue), the active loop
 * generation, running/cancelled loop ids and the recent thought history. The
 * drone controller carries none of this; {@link SessionManager} keeps one
 * instance per controller.</p>
 */
public final class DroneSession {
    private static final int MAX_NOTES = 8;
    private static final int MAX_THOUGHTS = 12;

    private boolean active;
    private int loopId;
    private String prompt = "";
    private String summary = "";
    private long startedAtMs;
    private final List<String> notes = new ArrayList<>();

    private final AtomicInteger activeLoopId = new AtomicInteger(0);
    private final AtomicInteger activeScriptCount = new AtomicInteger(0);
    private final Set<Integer> runningLoopIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> cancelledLoopIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Integer, String> cancelReasons = new ConcurrentHashMap<>();
    private volatile String lastThought = "";
    private final Deque<String> thoughtHistory = new ConcurrentLinkedDeque<>();

    public synchronized void begin(int loopId, String prompt) {
        this.active = true;
        this.loopId = loopId;
        this.prompt = prompt != null ? prompt.trim() : "";
        this.summary = this.prompt;
        this.startedAtMs = System.currentTimeMillis();
        this.notes.clear();
    }

    public synchronized void addNote(String note) {
        if (note == null || note.trim().isEmpty()) return;
        String clean = note.trim();
        if (this.summary.isEmpty()) {
            this.summary = clean;
        } else if (!this.summary.contains(clean)) {
            this.summary = this.summary + " | note: " + clean;
        }
        this.notes.add(clean);
        while (this.notes.size() > MAX_NOTES) {
            this.notes.remove(0);
        }
    }

    public synchronized void complete() {
        this.active = false;
    }

    public synchronized void cancel() {
        this.active = false;
        this.notes.clear();
    }

    public synchronized boolean isActive() {
        return this.active;
    }

    public synchronized int getLoopId() {
        return this.loopId;
    }

    public synchronized String getPrompt() {
        return this.prompt;
    }

    public synchronized String getSummary() {
        return this.summary;
    }

    public synchronized List<String> getNotes() {
        return Collections.unmodifiableList(new ArrayList<>(this.notes));
    }

    public synchronized long getAgeMs() {
        return this.startedAtMs > 0 ? Math.max(0L, System.currentTimeMillis() - this.startedAtMs) : 0L;
    }

    public boolean isScriptRunning() {
        return this.activeScriptCount.get() > 0;
    }

    public int activeLoopId() {
        return this.activeLoopId.get();
    }

    public int nextLoopId() {
        return this.activeLoopId.incrementAndGet();
    }

    public int activeScriptCount() {
        return this.activeScriptCount.get();
    }

    public void scriptStarted() {
        this.activeScriptCount.incrementAndGet();
    }

    public void scriptFinished() {
        this.activeScriptCount.decrementAndGet();
    }

    public void registerLoop(int loopId) {
        if (loopId > 0) this.runningLoopIds.add(loopId);
    }

    public void unregisterLoop(int loopId) {
        if (loopId > 0) {
            this.runningLoopIds.remove(loopId);
            this.cancelledLoopIds.remove(loopId);
            this.cancelReasons.remove(loopId);
        }
    }

    public boolean isLoopCancelled(int loopId) {
        return loopId > 0 && this.cancelledLoopIds.contains(loopId);
    }

    /** Why a loop was cancelled: player_cancel, model_cancel, replaced_by_new_task, drone_despawned. */
    public String cancelReason(int loopId) {
        return loopId > 0 ? this.cancelReasons.get(loopId) : null;
    }

    public int cancelOtherLoops(int keepLoopId) {
        return cancelOtherLoops(keepLoopId, "cancelled");
    }

    public int cancelOtherLoops(int keepLoopId, String reason) {
        String cleanReason = reason != null && !reason.trim().isEmpty()
                ? reason.trim() : "cancelled";
        int count = 0;
        for (Integer id : this.runningLoopIds) {
            if (id != null && id != keepLoopId && this.cancelledLoopIds.add(id)) {
                this.cancelReasons.put(id, cleanReason);
                count++;
            }
        }
        return count;
    }

    public boolean hasActiveTaskOrLoop(int excludeLoopId) {
        for (Integer id : this.runningLoopIds) {
            if (id != null && id != excludeLoopId && !this.cancelledLoopIds.contains(id)) {
                return true;
            }
        }
        return this.activeScriptCount.get() > 0;
    }

    public String lastThought() {
        return this.lastThought;
    }

    public List<String> thoughtHistory() {
        return new ArrayList<>(this.thoughtHistory);
    }

    public void recordThought(int turn, String thought) {
        if (thought == null || thought.trim().isEmpty()) return;
        String clean = thought.trim();
        this.lastThought = clean;
        this.thoughtHistory.addLast("T" + turn + ": " + clean);
        while (this.thoughtHistory.size() > MAX_THOUGHTS) {
            this.thoughtHistory.pollFirst();
        }
    }
}
