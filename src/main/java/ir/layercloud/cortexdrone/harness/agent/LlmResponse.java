package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed assistant turn from an OpenAI-compatible chat completion.
 */
@SuppressWarnings("unused")
public final class LlmResponse {
    private final String content;
    private final String reasoning;
    private final List<ToolCall> toolCalls;
    private final String finishReason;
    private final JsonObject usage;

    public LlmResponse(String content, String reasoning, List<ToolCall> toolCalls,
                       String finishReason, JsonObject usage) {
        this.content = content != null ? content : "";
        this.reasoning = reasoning != null ? reasoning : "";
        this.toolCalls = toolCalls != null ? new ArrayList<>(toolCalls) : new ArrayList<ToolCall>();
        this.finishReason = finishReason != null ? finishReason : "";
        this.usage = usage;
    }

    public String content() {
        return this.content;
    }

    public String reasoning() {
        return this.reasoning;
    }

    public List<ToolCall> toolCalls() {
        return Collections.unmodifiableList(this.toolCalls);
    }

    public String finishReason() {
        return this.finishReason;
    }

    public JsonObject usage() {
        return this.usage;
    }

    public boolean hasToolCalls() {
        return !this.toolCalls.isEmpty();
    }

    public boolean isEmpty() {
        return this.content.trim().isEmpty() && this.toolCalls.isEmpty();
    }
}
