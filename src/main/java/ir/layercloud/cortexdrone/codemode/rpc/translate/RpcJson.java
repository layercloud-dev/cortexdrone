package ir.layercloud.cortexdrone.codemode.rpc.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Shared JSON response helpers for service boundaries. */
@UtilityClass
public final class RpcJson {
    public static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    public static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message != null ? message : "Action failed");
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
