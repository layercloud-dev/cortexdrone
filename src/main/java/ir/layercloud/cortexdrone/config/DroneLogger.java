package ir.layercloud.cortexdrone.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.model.ChatRecord;
import ir.layercloud.cortexdrone.model.DebugEntry;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DroneLogger {
    private static final int MAX_DEBUG_ENTRIES = 400;
    private static final int MAX_CHAT_RECORDS = 50;
    private static final int MAX_DEBUG_FILE_LINE_CHARS = 4096;
    private static final long MAX_DEBUG_LOG_BYTES = 64L * 1024L * 1024L;
    private static final long DEBUG_LOG_ROTATION_CHECK_MS = 3500L;
    private static final int MAX_LOG_FLUSH_BYTES = 64 * 1024;
    private final StringBuilder pendingLogText = new StringBuilder(8192);
    private long lastLogRotationCheckMs = 0L;
    private boolean logFlushScheduled = false;

    private final Deque<DebugEntry> debugLog = new ArrayDeque<>();
    private final Deque<ChatRecord> recentChat = new ArrayDeque<>();
    @Getter
    private final Set<String> subscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Getter
    @Setter
    private volatile File debugLogFile;

    public void log(String category, DroneController ctrl, String message, String details) {
        final DebugEntry entry = new DebugEntry(
                category,
                ctrl != null ? ctrl.id : 0,
                ctrl != null ? ctrl.name : "System",
                message,
                details
        );

        synchronized (this.debugLog) {
            this.debugLog.addLast(entry);
            while (this.debugLog.size() > MAX_DEBUG_ENTRIES) {
                this.debugLog.removeFirst();
            }
        }

        if (!this.subscribers.isEmpty()) {
            String chatLine = EnumChatFormatting.DARK_GRAY + "[DroneDebug:" + category + "] "
                    + EnumChatFormatting.AQUA + (ctrl != null ? ctrl.name : "Sys") + ": "
                    + EnumChatFormatting.GRAY + truncateForChat(message)
                    + (details != null && !details.isEmpty() ? EnumChatFormatting.DARK_GRAY + " (" + truncateForChat(details) + ")" : "");
            for (String subPlayer : this.subscribers) {
                EntityPlayerMP p = CortexDroneMod.findPlayer(subPlayer);
                if (p != null) {
                    CortexDroneMod.sendMsg(p, chatLine);
                }
            }
        }

        appendToDebugFile(entry);
    }

    private void appendToDebugFile(DebugEntry entry) {
        final File f = this.debugLogFile;
        if (f == null) return;
        String line = truncateLine(entry.toLogLine());
        boolean needSchedule;
        synchronized (this.pendingLogText) {
            this.pendingLogText.append(line).append('\n');
            if (this.pendingLogText.length() > 200 * 1024) {
                int keep = 100 * 1024;
                this.pendingLogText.delete(0, this.pendingLogText.length() - keep);
                this.pendingLogText.insert(0, "...[log buffer coalesced, dropped prefix]\n");
            }
            needSchedule = !this.logFlushScheduled;
            if (needSchedule) {
                this.logFlushScheduled = true;
            }
        }
        if (needSchedule) {
            CortexDroneMod.HTTP_POOL.submit(new Runnable() {
                @Override
                public void run() {
                    flushPendingLog(600L);
                }
            });
        }
    }

    private void flushPendingLog(long delayMs) {
        try {
            if (delayMs > 0L) {
                Thread.sleep(delayMs);
            }
        } catch (InterruptedException ignored) {
        }
        String snapshot;
        synchronized (this.pendingLogText) {
            this.logFlushScheduled = false;
            if (this.pendingLogText.length() == 0) {
                return;
            }
            snapshot = this.pendingLogText.toString();
            this.pendingLogText.setLength(0);
        }
        File f = this.debugLogFile;
        if (f == null) return;
        // Coalesce+bound: old code opened a FileWriter per log line; under STUCK
        // spam that is thousands of opens + threads. Now one append per flush.
        maybeRotateDebugLog(f);
        try (FileWriter fw = new FileWriter(f, true)) {
            String capped = snapshot.length() > MAX_LOG_FLUSH_BYTES
                    ? snapshot.substring(0, MAX_LOG_FLUSH_BYTES) + "\n...[log flush truncated +" + (snapshot.length() - MAX_LOG_FLUSH_BYTES) + " chars]\n"
                    : snapshot;
            fw.write(capped);
        } catch (Exception ignored) {
        }
        synchronized (this.pendingLogText) {
            // If more lines arrived while writing, schedule another coalesced flush.
            if (this.pendingLogText.length() > 0 && !this.logFlushScheduled) {
                this.logFlushScheduled = true;
                CortexDroneMod.HTTP_POOL.submit(new Runnable() {
                    @Override
                    public void run() {
                        flushPendingLog(600L);
                    }
                });
            }
        }
    }

    private void maybeRotateDebugLog(File f) {
        long now = System.currentTimeMillis();
        if (now - this.lastLogRotationCheckMs < DEBUG_LOG_ROTATION_CHECK_MS) {
            return;
        }
        this.lastLogRotationCheckMs = now;
        try {
            if (f.length() > MAX_DEBUG_LOG_BYTES) {
                File old = new File(f.getAbsolutePath() + ".1");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
        } catch (Exception ignored) {
        }
    }

    private static String truncateForChat(String s) {
        if (s == null) return "";
        int max = 300;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static String truncateLine(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_DEBUG_FILE_LINE_CHARS) return s;
        return s.substring(0, MAX_DEBUG_FILE_LINE_CHARS) + "...(+" + (s.length() - MAX_DEBUG_FILE_LINE_CHARS) + " chars)";
    }

    public void recordChat(String sender, String message) {
        synchronized (this.recentChat) {
            this.recentChat.addLast(new ChatRecord(sender, message));
            while (this.recentChat.size() > MAX_CHAT_RECORDS) {
                this.recentChat.removeFirst();
            }
        }
    }

    public List<DebugEntry> getRecentDebugEntries(int maxCount) {
        List<DebugEntry> snap = new ArrayList<>();
        synchronized (this.debugLog) {
            snap.addAll(this.debugLog);
        }
        int start = Math.max(0, snap.size() - maxCount);
        return snap.subList(start, snap.size());
    }

    public JsonArray toDebugJsonArray(int maxCount) {
        JsonArray arr = new JsonArray();
        for (DebugEntry entry : getRecentDebugEntries(maxCount)) {
            arr.add(truncatedJson(entry, 800, 800));
        }
        return arr;
    }

    public JsonArray toDebugJsonArray(int maxCount, int maxChars) {
        JsonArray arr = new JsonArray();
        for (DebugEntry entry : getRecentDebugEntries(maxCount)) {
            arr.add(truncatedJson(entry, maxChars, maxChars));
        }
        return arr;
    }

    // Cap what the HTTP endpoints emit: JS Code Mode dumps (chest scans, inventories) can be
    // 100+ KB per debug line, and /state polls would re-serialize them every second.
    // Full entries still land in the debug log FILE (toLogLine) for forensics.
    private static JsonObject truncatedJson(DebugEntry entry, int maxMessageChars, int maxDetailsChars) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", entry.getTimestamp());
        o.addProperty("time", entry.getTime());
        o.addProperty("category", entry.getCategory());
        o.addProperty("droneId", entry.getDroneId());
        o.addProperty("droneName", entry.getDroneName());
        o.addProperty("message", capText(entry.getMessage(), maxMessageChars));
        if (entry.getDetails() != null && !entry.getDetails().isEmpty()) {
            o.addProperty("details", capText(entry.getDetails(), maxDetailsChars));
        }
        return o;
    }

    private static String capText(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(+" + (s.length() - max) + " chars)";
    }

    public JsonArray toChatJsonArray() {
        JsonArray arr = new JsonArray();
        synchronized (this.recentChat) {
            for (ChatRecord cr : this.recentChat) {
                arr.add(cr.toJson());
            }
        }
        return arr;
    }

    public void clearDebugLog() {
        synchronized (this.debugLog) {
            this.debugLog.clear();
        }
        File f = this.debugLogFile;
        if (f != null && f.exists()) {
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(new byte[0]);
            } catch (Exception ignored) {
            }
        }
    }
}
