package ir.layercloud.cortexdrone.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One task / one session.
 *
 * <p>The controller owns exactly one of these. Starting a new task replaces the
 * previous one; side notes are attached to the running task instead of being
 * queued as future instructions. There is deliberately no instruction queue.</p>
 */
public final class TaskSession {
    private boolean active;
    private int loopId;
    private String prompt = "";
    private String summary = "";
    private long startedAtMs;
    private long lastUpdateMs;
    private final List<String> notes = new ArrayList<String>();
    private static final int MAX_NOTES = 8;

    public synchronized void begin(int loopId, String prompt) {
        this.active = true;
        this.loopId = loopId;
        this.prompt = prompt != null ? prompt.trim() : "";
        this.summary = this.prompt;
        this.startedAtMs = System.currentTimeMillis();
        this.lastUpdateMs = this.startedAtMs;
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
        this.lastUpdateMs = System.currentTimeMillis();
    }

    public synchronized void updateSummary(String text) {
        if (text == null || text.trim().isEmpty()) return;
        this.summary = text.trim();
        this.lastUpdateMs = System.currentTimeMillis();
    }

    public synchronized void complete() {
        this.active = false;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    public synchronized void cancel() {
        this.active = false;
        this.lastUpdateMs = System.currentTimeMillis();
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

    public synchronized long getStartedAtMs() {
        return this.startedAtMs;
    }

    public synchronized long getLastUpdateMs() {
        return this.lastUpdateMs;
    }

    public synchronized List<String> getNotes() {
        return Collections.unmodifiableList(new ArrayList<String>(this.notes));
    }

    public synchronized long getAgeMs() {
        return this.startedAtMs > 0 ? Math.max(0L, System.currentTimeMillis() - this.startedAtMs) : 0L;
    }
}
