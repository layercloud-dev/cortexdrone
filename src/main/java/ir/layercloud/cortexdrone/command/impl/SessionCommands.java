package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.harness.session.SessionJournal;
import ir.layercloud.cortexdrone.harness.session.SessionOutcome;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ir.layercloud.cortexdrone.CortexDroneMod;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.List;
import java.util.Map;

/**
 * {@code /drone sessions} — browse the per-task transcript journal.
 *
 * <p>Read-only reporting extracted from {@link DroneCommand}: listing, stats and
 * a single-session detail view. It owns no task state, so it needs only the
 * journal and the chat sender.</p>
 */
public final class SessionCommands {
    /** Longest final reply shown in chat. */
    private static final int REPLY_PREVIEW = 300;
    /** Longest prompt shown in the list view. */
    private static final int PROMPT_PREVIEW = 90;
    /** Default number of sessions listed. */
    private static final int DEFAULT_LIMIT = 10;

    private SessionCommands() {
    }

    public static void handle(ICommandSender sender, SessionJournal journal, String[] args) {
        if (journal == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Session journal is not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        if ("stats".equals(action)) {
            stats(sender, journal);
            return;
        }
        if ("show".equals(action)) {
            show(sender, journal, args);
            return;
        }
        list(sender, journal, action, args);
    }

    private static void stats(ICommandSender sender, SessionJournal journal) {
        Map<String, Integer> counts = journal.counts();
        int total = 0;
        for (Integer value : counts.values()) total += value;
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD
                + "=== Session Journal (" + total + " recorded) ===");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + entry.getKey()
                    + EnumChatFormatting.GRAY + ": " + EnumChatFormatting.WHITE + entry.getValue());
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Folder: "
                + journal.sessionsRoot().getAbsolutePath());
    }

    private static void show(ICommandSender sender, SessionJournal journal, String[] args) {
        String key = args.length >= 3 ? join(args, 2) : "";
        JsonObject entry = journal.find(key);
        if (entry == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] No session matched '" + key + "'. Use /drone sessions list.");
            return;
        }
        File file = journal.fileFor(entry);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Session "
                + string(entry, "id") + " ===");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "outcome: "
                + EnumChatFormatting.WHITE + string(entry, "outcome")
                + EnumChatFormatting.AQUA + "  topic: " + EnumChatFormatting.WHITE
                + string(entry, "topic")
                + EnumChatFormatting.AQUA + "  reason: " + EnumChatFormatting.WHITE
                + string(entry, "reason"));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "drone: "
                + EnumChatFormatting.WHITE + string(entry, "drone")
                + EnumChatFormatting.AQUA + "  owner: " + EnumChatFormatting.WHITE
                + string(entry, "owner")
                + EnumChatFormatting.AQUA + "  player: " + EnumChatFormatting.WHITE
                + string(entry, "player"));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "turns/tools/errors: "
                + EnumChatFormatting.WHITE + integer(entry, "turns") + "/"
                + integer(entry, "tool_calls") + "/" + integer(entry, "tool_errors")
                + EnumChatFormatting.AQUA + "  world actions: "
                + EnumChatFormatting.WHITE + integer(entry, "world_actions"));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "prompt: "
                + EnumChatFormatting.WHITE + string(entry, "prompt"));
        JsonObject full = readJson(file);
        if (full != null && full.has("final_reply")) {
            String reply = string(full, "final_reply");
            if (!reply.isEmpty()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "final: "
                        + EnumChatFormatting.WHITE + cap(reply, REPLY_PREVIEW));
            }
        }
        if (file != null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "file: "
                    + file.getAbsolutePath());
        }
    }

    private static void list(ICommandSender sender, SessionJournal journal, String action, String[] args) {
        SessionOutcome filter = SessionOutcome.fromId(action);
        int limit = DEFAULT_LIMIT;
        if (filter != null) {
            if (args.length >= 3) limit = parsePositive(args[2], DEFAULT_LIMIT);
        } else if ("list".equals(action)) {
            if (args.length >= 3) {
                SessionOutcome maybe = SessionOutcome.fromId(args[2]);
                if (maybe != null) {
                    filter = maybe;
                    if (args.length >= 4) limit = parsePositive(args[3], DEFAULT_LIMIT);
                } else {
                    limit = parsePositive(args[2], DEFAULT_LIMIT);
                }
            }
        } else {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "Usage: /drone sessions [list [success|failure|...] [n]|stats|show <id>]");
            return;
        }

        List<JsonObject> entries = journal.recent(limit, filter);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Sessions ("
                + (filter != null ? filter.id() : "all") + ", last " + entries.size() + ") ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                    + "Nothing recorded yet. Sessions are written after each task.");
            return;
        }
        long now = System.currentTimeMillis();
        for (JsonObject entry : entries) {
            long at = entry.has("at_ms") ? entry.get("at_ms").getAsLong() : 0L;
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "["
                    + string(entry, "outcome") + "/" + string(entry, "topic") + "] "
                    + EnumChatFormatting.GRAY + ago(now, at) + " "
                    + EnumChatFormatting.WHITE + string(entry, "drone")
                    + EnumChatFormatting.GRAY + " " + integer(entry, "turns") + "t/"
                    + integer(entry, "tool_calls") + "c/" + integer(entry, "tool_errors") + "e \""
                    + cap(string(entry, "prompt"), PROMPT_PREVIEW) + "\""
                    + EnumChatFormatting.DARK_GRAY + " id=" + string(entry, "id"));
        }
    }

    private static JsonObject readJson(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new FileReader(file)) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    static int integer(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsInt() : 0;
    }

    static int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String ago(long now, long then) {
        if (then <= 0) return "?";
        long seconds = Math.max(0L, (now - then) / 1000L);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    static String cap(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    static String join(String[] args, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(args[i]);
        }
        return out.toString();
    }
}
