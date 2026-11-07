package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.CortexDroneMod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded conversation log for one task.
 *
 * <p>Messages keep the exact OpenAI shape (assistant tool_calls followed by
 * matching tool results). Tool payloads are recursively truncated before they
 * enter the prompt, and old turns are replaced by a compact digest instead of
 * dropping the original task or orphaning a tool result.</p>
 */
public final class Conversation {
    private static final int KEEP_RECENT = 12;
    private static final int DIGEST_ENTRY_CHARS = 400;
    private static final int MAX_STRING_CHARS = 1_200;
    private static final int MAX_ARRAY_ITEMS = 24;

    private final List<JsonObject> messages = new ArrayList<>();
    private final int maxChars;
    private final int maxToolChars;
    private int compactions;

    public Conversation(String systemPrompt, String contextMessage, String userPrompt,
                        int maxChars, int maxToolChars) {
        this.maxChars = Math.max(16_000, maxChars);
        this.maxToolChars = Math.max(1_000, maxToolChars);
        system(systemPrompt);
        system(contextMessage);
        user(userPrompt);
    }

    public List<JsonObject> messages() {
        return this.messages;
    }

    public void system(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "system");
        message.addProperty("content", content != null ? content : "");
        this.messages.add(message);
    }

    public void user(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", content != null ? content : "");
        this.messages.add(message);
    }

    public void assistant(LlmResponse response) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", response.content());
        // Thinking models require the previous turn's reasoning to be replayed
        // verbatim, especially when the turn carried tool calls; omitting it
        // fails the next request with "reasoning_content ... must be passed
        // back to the API". Providers that reject the field strip it in the
        // gateway, so replaying it is safe.
        if (!response.reasoning().isEmpty()) {
            message.addProperty("reasoning_content", response.reasoning());
        }
        if (response.hasToolCalls()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : response.toolCalls()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                function.addProperty("arguments", call.arguments());
                JsonObject callObject = new JsonObject();
                callObject.addProperty("id", call.id());
                callObject.addProperty("type", "function");
                callObject.add("function", function);
                calls.add(callObject);
            }
            message.add("tool_calls", calls);
        }
        this.messages.add(message);
    }

    public void toolResult(ToolCall call, JsonObject result) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "tool");
        message.addProperty("tool_call_id", call.id());
        message.addProperty("name", call.name());
        message.addProperty("content", CortexDroneMod.GSON.toJson(cap(result)));
        this.messages.add(message);
    }

    public void toolError(ToolCall call, String error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        result.addProperty("code", "invalid_tool_arguments");
        result.addProperty("hint", "Call the tool again with a valid JSON object that matches its schema.");
        result.addProperty("retryable", true);
        toolResult(call, result);
    }

    public boolean estimateTooLarge() {
        return estimate() > this.maxChars;
    }

    public int compactions() {
        return this.compactions;
    }

    public int estimate() {
        int total = 0;
        for (JsonObject message : this.messages) {
            total += 24;
            total += message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString().length() : 0;
            total += message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()
                    ? message.get("reasoning_content").getAsString().length() : 0;
            if (message.has("tool_calls")) total += message.get("tool_calls").toString().length();
        }
        return total;
    }

    /**
     * Replaces the middle of the transcript with a digest. The first system
     * prompt, the live context and the original task always survive, and the
     * retained suffix never starts with an orphaned tool message.
     */
    public void compactIfNeeded() {
        if (this.messages.size() < 8 || !estimateTooLarge()) return;
        int keepFrom = Math.max(3, this.messages.size() - KEEP_RECENT);
        while (keepFrom < this.messages.size()) {
            JsonObject message = this.messages.get(keepFrom);
            String role = message.has("role") ? message.get("role").getAsString() : "";
            if (!"tool".equals(role)) break;
            keepFrom++;
        }
        if (keepFrom <= 3) return;

        StringBuilder digest = new StringBuilder();
        digest.append("[Compacted transcript — earlier tool turns summarized to keep the context bounded]\n");
        for (int i = 3; i < keepFrom; i++) {
            JsonObject message = this.messages.get(i);
            String role = message.has("role") ? message.get("role").getAsString() : "?";
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";
            if (content.length() > DIGEST_ENTRY_CHARS) {
                content = content.substring(0, DIGEST_ENTRY_CHARS) + "...";
            }
            digest.append('[').append(role).append("] ").append(content.replace('\n', ' ')).append('\n');
        }

        List<JsonObject> rebuilt = new ArrayList<>();
        rebuilt.add(this.messages.get(0));
        rebuilt.add(this.messages.get(1));
        rebuilt.add(this.messages.get(2));
        JsonObject digestMessage = new JsonObject();
        digestMessage.addProperty("role", "system");
        digestMessage.addProperty("content", digest.toString());
        rebuilt.add(digestMessage);
        for (int i = keepFrom; i < this.messages.size(); i++) rebuilt.add(this.messages.get(i));

        this.messages.clear();
        this.messages.addAll(rebuilt);
        this.compactions++;
    }

    private JsonObject cap(JsonObject result) {
        JsonElement bounded = bound(result, 0);
        JsonObject capped = bounded != null && bounded.isJsonObject()
                ? bounded.getAsJsonObject() : new JsonObject();
        String serialized = CortexDroneMod.GSON.toJson(capped);
        if (serialized.length() <= this.maxToolChars) return capped;
        JsonObject small = new JsonObject();
        small.addProperty("ok", capped.has("ok") ? capped.get("ok").getAsBoolean() : true);
        small.addProperty("truncated", true);
        small.addProperty("original_chars", serialized.length());
        small.addProperty("preview", serialized.substring(0, Math.min(2_000, serialized.length())));
        small.addProperty("hint", "The result was truncated. Use a tighter filter/radius/limit, "
                + "or process it inside execute and return only what you need.");
        return small;
    }

    private JsonElement bound(JsonElement element, int depth) {
        if (element == null || element.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                String text = element.getAsString();
                if (text.length() > MAX_STRING_CHARS) {
                    return new JsonPrimitive(text.substring(0, MAX_STRING_CHARS)
                            + "...(+ " + (text.length() - MAX_STRING_CHARS) + " chars)");
                }
            }
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            JsonArray bounded = new JsonArray();
            int limit = Math.min(MAX_ARRAY_ITEMS, array.size());
            for (int i = 0; i < limit; i++) bounded.add(bound(array.get(i), depth + 1));
            if (array.size() > limit) {
                JsonObject marker = new JsonObject();
                marker.addProperty("__truncated_items", array.size() - limit);
                bounded.add(marker);
            }
            return bounded;
        }
        if (depth > 6) return new JsonPrimitive("[...]");
        JsonObject object = element.getAsJsonObject();
        JsonObject bounded = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            bounded.add(entry.getKey(), bound(entry.getValue(), depth + 1));
        }
        return bounded;
    }
}
