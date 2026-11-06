package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;

import java.util.Map;

/**
 * Shared JSON response helpers for service boundaries.
 */
@UtilityClass
public final class ToolJson {
    public static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    public static JsonObject error(String message) {
        return error("tool_error", message, null, true);
    }

    /**
     * Standard error envelope returned to the model.
     *
     * <p>{@code code} is a stable machine-readable reason, {@code hint} tells
     * the model what to change, and {@code retryable} says whether the exact
     * same call can succeed later. The agent loop never parses prose: it shows
     * this envelope to the model verbatim.</p>
     */
    public static JsonObject error(String code, String message, String hint, boolean retryable) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message != null ? message : "Action failed");
        result.addProperty("code", code != null ? code : "tool_error");
        if (hint != null && !hint.trim().isEmpty()) {
            result.addProperty("hint", hint);
        }
        result.addProperty("retryable", retryable);
        return result;
    }

    public static JsonObject point(int x, int y, int z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        point.addProperty("z", z);
        return point;
    }

    public static void add(JsonObject target, String key, Object value) {
        if (value == null) return;
        if (value instanceof JsonElement) {
            target.add(key, (JsonElement) value);
        } else if (value instanceof String) {
            target.addProperty(key, (String) value);
        } else if (value instanceof Number) {
            target.addProperty(key, (Number) value);
        } else if (value instanceof Boolean) {
            target.addProperty(key, (Boolean) value);
        } else {
            target.addProperty(key, String.valueOf(value));
        }
    }

    public static void addAll(JsonObject target, Map<String, Object> values) {
        if (values == null) return;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            add(target, entry.getKey(), entry.getValue());
        }
    }
}
