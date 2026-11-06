package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;

/**
 * Small, null-safe JSON accessors used by service request boundaries.
 * Actions never see {@link JsonObject}.
 */
@UtilityClass
public final class ActionArgs {
    public static JsonObject safe(JsonObject args) {
        return args != null ? args : new JsonObject();
    }

    public static boolean has(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull();
    }

    public static String string(JsonObject args, String key, String fallback) {
        return has(args, key) ? args.get(key).getAsString() : fallback;
    }

    public static int integer(JsonObject args, String key, int fallback) {
        return has(args, key) ? args.get(key).getAsInt() : fallback;
    }

    public static int clampedInt(JsonObject args, String key, int fallback, int min, int max) {
        int value = integer(args, key, fallback);
        return Math.max(min, Math.min(max, value));
    }

    public static double decimal(JsonObject args, String key, double fallback) {
        return has(args, key) ? args.get(key).getAsDouble() : fallback;
    }

    public static float decimalFloat(JsonObject args, String key, float fallback) {
        return has(args, key) ? args.get(key).getAsFloat() : fallback;
    }

    public static boolean flag(JsonObject args, String key, boolean fallback) {
        return has(args, key) ? args.get(key).getAsBoolean() : fallback;
    }

    public static JsonObject object(JsonObject args, String key) {
        JsonElement element = args != null ? args.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
