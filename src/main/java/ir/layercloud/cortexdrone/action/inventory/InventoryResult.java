package ir.layercloud.cortexdrone.action.inventory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/** Immutable result of one inventory action. */
@Value
@Builder
public class InventoryResult {
    boolean ok;
    String error;

    @Singular("field")
    Map<String, Object> fields;

    public static InventoryResult success(Object... fields) {
        return build(true, null, fields);
    }

    public static InventoryResult failure(String error, Object... fields) {
        return build(false, error, fields);
    }

    public static InventoryResult fromJson(JsonObject json) {
        InventoryResultBuilder builder = builder();
        builder.ok(json.has("ok") && json.get("ok").getAsBoolean());
        if (json.has("error") && !json.get("error").isJsonNull()) {
            builder.error(json.get("error").getAsString());
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if ("ok".equals(key) || "error".equals(key)) continue;
            builder.field(key, entry.getValue());
        }
        return builder.build();
    }

    private static InventoryResult build(boolean ok, String error, Object... fields) {
        InventoryResultBuilder builder = builder().ok(ok).error(error);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.field(String.valueOf(fields[i]), fields[i + 1]);
        }
        return builder.build();
    }
}
