package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;

/**
 * Small, null-safe JSON accessors used by service request boundaries.
 * Actions never see {@link JsonObject}.
 */
@UtilityClass
public final class ToolArgs {
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

    public static JsonObject object(JsonObject args, String key) {
        JsonElement element = args != null ? args.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Adds snake_case schema keys from camelCase caller keys before validation. */
    public static JsonObject aliasesForSchema(JsonObject args, JsonObject parameters) {
        JsonObject copy = new JsonObject();
        if (args == null) return copy;
        for (java.util.Map.Entry<String, JsonElement> entry : args.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        if (parameters == null || !parameters.has("properties")) return copy;
        for (String key : parameters.getAsJsonObject("properties").entrySet().stream()
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toList())) {
            if (copy.has(key)) continue;
            String camel = camelCase(key);
            if (copy.has(camel)) copy.add(key, copy.get(camel));
        }
        return copy;
    }

    private static String camelCase(String key) {
        StringBuilder camel = new StringBuilder(key.length());
        boolean upper = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_') { upper = true; continue; }
            camel.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return camel.toString();
    }

    /**
     * Adds camelCase aliases for snake_case keys. The public tool schema uses
     * snake_case; legacy domain handlers read camelCase. Both remain valid.
     */
    public static JsonObject camelAliases(JsonObject args) {
        if (args == null) return new JsonObject();
        JsonObject copy = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> entry : args.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        for (java.util.Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            int underscore = key.indexOf('_');
            if (underscore < 0) continue;
            StringBuilder camel = new StringBuilder(key.length());
            boolean upper = false;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c == '_') {
                    upper = true;
                    continue;
                }
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            String camelKey = camel.toString();
            if (!copy.has(camelKey)) copy.add(camelKey, entry.getValue());
        }
        return copy;
    }
}
