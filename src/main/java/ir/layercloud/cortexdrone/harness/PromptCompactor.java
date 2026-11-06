package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the agent message list bounded and produces a compacted continuation
 * context when the prompt gets too large or the backend reports a context-length
 * error. The compacted form preserves the system instructions, the original user
 * task, and a short digest of the most recent tool results, so a replacement
 * loop can continue the same job without carrying the whole transcript.
 *
 * <p>Gson 2.2.4 (bundled with 1.7.10) has no JsonArray.remove/clear, so compaction
 * returns a fresh array and the caller swaps its reference.
 */
final class PromptCompactor {
    static final int MAX_CONTEXT_CHARS = 60000;
    private static final int MAX_SYSTEM_CHARS = 16000;
    private static final int DIGEST_MESSAGES = 10;
    private static final int DIGEST_ENTRY_CHARS = 700;
    private static final AtomicBoolean CONTEXT_OVERFLOW = new AtomicBoolean(false);

    private PromptCompactor() {
    }

    static void markContextOverflow() {
        CONTEXT_OVERFLOW.set(true);
    }

    static boolean consumeContextOverflow() {
        return CONTEXT_OVERFLOW.getAndSet(false);
    }

    static int estimateChars(JsonArray messages) {
        if (messages == null) return 0;
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            JsonObject m = asObject(messages.get(i));
            if (m == null || !m.has("content") || m.get("content").isJsonNull()) continue;
            total += m.get("content").getAsString().length() + 24;
        }
        return total;
    }

    static JsonArray compactIfLarge(JsonArray messages) {
        if (messages == null || messages.size() < 3) return messages;
        if (estimateChars(messages) <= MAX_CONTEXT_CHARS) return messages;
        return compact(messages);
    }

    static JsonArray compact(JsonArray messages) {
        if (messages == null || messages.size() < 3) return messages;
        String system = content(messages.get(0));
        String user = content(messages.get(1));
        if (system.length() > MAX_SYSTEM_CHARS) {
            system = system.substring(0, MAX_SYSTEM_CHARS) + "\n...[system prompt truncated during compaction]";
        }
        StringBuilder digest = new StringBuilder();
        int from = Math.max(2, messages.size() - DIGEST_MESSAGES);
        for (int i = from; i < messages.size(); i++) {
            JsonObject m = asObject(messages.get(i));
            if (m == null) continue;
            String role = m.has("role") && !m.get("role").isJsonNull() ? m.get("role").getAsString() : "?";
            String c = content(m);
            if (c.isEmpty()) continue;
            if (c.length() > DIGEST_ENTRY_CHARS) c = c.substring(0, DIGEST_ENTRY_CHARS) + "...";
            digest.append('[').append(role).append("] ").append(c).append('\n');
        }

        JsonArray rebuilt = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system
            + "\n\n[CONTEXT COMPACTED: earlier intermediate output was summarized. Continue the same task.]");
        rebuilt.add(sys);

        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user
            + "\n\n[HEARTBEAT PROGRESS DIGEST - continue from here, do not repeat completed steps]\n"
            + digest.toString());
        rebuilt.add(usr);
        return rebuilt;
    }

    private static JsonObject asObject(JsonElement el) {
        try {
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String content(JsonElement el) {
        JsonObject m = asObject(el);
        if (m == null || !m.has("content") || m.get("content").isJsonNull()) return "";
        try {
            return m.get("content").getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
