package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Validates and coerces tool arguments against the declared JSON schema.
 *
 * <p>The model is allowed to be sloppy in predictable ways (a numeric string
 * for an integer, a single value instead of an array, a JSON string instead of
 * an object). Those are coerced. Anything that cannot be coerced produces an
 * {@code invalid_arguments} error with the exact field and expected type, so
 * the next model turn can fix it.</p>
 */
public final class ToolValidator {
    private ToolValidator() {
    }

    /** @return {@code null} when arguments are valid; otherwise an error envelope. */
    public static JsonObject validate(ToolSpec spec, JsonObject args) {
        JsonObject parameters = spec.getParameters();
        JsonObject properties = parameters.has("properties")
                ? parameters.getAsJsonObject("properties") : new JsonObject();
        JsonArray required = parameters.has("required")
                ? parameters.getAsJsonArray("required") : new JsonArray();

        for (JsonElement element : required) {
            String name = element.getAsString();
            if (!args.has(name) || args.get(name).isJsonNull()) {
                return missing(spec, name, properties);
            }
        }

        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            String name = entry.getKey();
            if (!args.has(name) || args.get(name).isJsonNull()) continue;
            JsonObject property = entry.getValue().getAsJsonObject();
            String expected = typeOf(property);
            JsonElement coerced = coerce(args.get(name), expected);
            if (coerced == null) {
                return ToolJson.error("invalid_arguments",
                        "Argument '" + name + "' must be " + article(expected) + " " + expected
                                + " but was " + describe(args.get(name)) + ".",
                        "Retry " + spec.getName() + " with a valid '" + name + "'.", true);
            }
            args.add(name, coerced);
            JsonArray allowed = property.has("enum") ? property.getAsJsonArray("enum") : null;
            if (allowed != null && !allowedContains(allowed, coerced)) {
                return ToolJson.error("invalid_arguments",
                        "Argument '" + name + "' has value '" + coerced.getAsString()
                                + "' but must be one of " + allowed + ".",
                        "Use one of the documented values for '" + name + "'.", true);
            }
        }
        return null;
    }

    private static JsonObject missing(ToolSpec spec, String name, JsonObject properties) {
        JsonObject property = properties.has(name) ? properties.getAsJsonObject(name) : null;
        String expected = property != null ? typeOf(property) : "value";
        String description = property != null && property.has("description")
                ? property.get("description").getAsString() : "";
        return ToolJson.error("invalid_arguments",
                "Missing required argument '" + name + "' (" + expected + ")"
                        + (description.isEmpty() ? "." : ": " + description),
                "Call " + spec.getName() + " again and provide '" + name + "'.", true);
    }

    private static String typeOf(JsonObject property) {
        if (property == null || !property.has("type")) return "value";
        return property.get("type").getAsString();
    }

    private static JsonElement coerce(JsonElement value, String type) {
        switch (type) {
            case "integer": {
                Long number = asLong(value);
                return number != null ? new JsonPrimitive(number) : null;
            }
            case "number": {
                Double number = asDouble(value);
                return number != null ? new JsonPrimitive(number) : null;
            }
            case "boolean": {
                Boolean bool = asBoolean(value);
                return bool != null ? new JsonPrimitive(bool) : null;
            }
            case "string": {
                if (value.isJsonPrimitive()) return new JsonPrimitive(value.getAsString());
                return null;
            }
            case "array": {
                if (value.isJsonArray()) return value;
                if (value.isJsonPrimitive()) {
                    JsonArray wrapped = new JsonArray();
                    wrapped.add(value.getAsJsonPrimitive());
                    return wrapped;
                }
                return null;
            }
            case "object": {
                if (value.isJsonObject()) return value;
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    try {
                        JsonElement parsed = new JsonParser().parse(value.getAsString());
                        if (parsed != null && parsed.isJsonObject()) return parsed;
                    } catch (Exception ignored) {
                        return null;
                    }
                }
                return null;
            }
            default:
                return value;
        }
    }

    private static Long asLong(JsonElement value) {
        try {
            if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    double number = primitive.getAsDouble();
                    return inIntRange(number) ? (long) number : null;
                }
                if (primitive.isString()) {
                    String text = primitive.getAsString().trim();
                    if (text.isEmpty()) return null;
                    double parsed = Double.parseDouble(text);
                    return inIntRange(parsed) ? (long) parsed : null;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Integer schemas are backed by Java {@code int} coordinates and counts.
     * A wider JSON number would pass validation and then truncate at the
     * handler, silently targeting a different block, so reject it here.
     */
    private static boolean inIntRange(double number) {
        return number == Math.rint(number)
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
    }

    private static Double asDouble(JsonElement value) {
        try {
            if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) return primitive.getAsDouble();
                if (primitive.isString()) {
                    String text = primitive.getAsString().trim();
                    if (!text.isEmpty()) return Double.parseDouble(text);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Boolean asBoolean(JsonElement value) {
        try {
            if (!value.isJsonPrimitive()) return null;
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsInt() != 0;
            if (primitive.isString()) {
                String text = primitive.getAsString().trim().toLowerCase();
                if (text.equals("true") || text.equals("on") || text.equals("yes") || text.equals("1")) return true;
                if (text.equals("false") || text.equals("off") || text.equals("no") || text.equals("0")) return false;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean allowedContains(JsonArray allowed, JsonElement candidate) {
        for (JsonElement value : allowed) {
            if (value.equals(candidate)) return true;
        }
        return false;
    }

    private static String describe(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        String text = value.toString();
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private static String article(String type) {
        return ("integer".equals(type) || "array".equals(type) || "object".equals(type)) ? "an" : "a";
    }
}
