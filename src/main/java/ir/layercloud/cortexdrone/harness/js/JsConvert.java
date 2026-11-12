package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.*;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.mozilla.javascript.*;

import java.util.List;
import java.util.Map;

/**
 * Converts between Rhino values and Gson trees. JavaScript never sees Java
 * objects from a tool call: results are plain JS objects, which keeps model
 * scripts simple and serialization predictable.
 */
@SuppressWarnings("unused")
final class JsConvert {
    private JsConvert() {
    }

    static JsonElement toJson(Context cx, Scriptable scope, Object value) {
        if (value == null || value instanceof Undefined) return JsonNull.INSTANCE;
        if (value instanceof CharSequence) return new JsonPrimitive(value.toString());
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        try {
            Object serialized = NativeJSON.stringify(cx, scope, value, null, null);
            if (serialized instanceof CharSequence) {
                String text = serialized.toString();
                if (!text.isEmpty() && !"null".equals(text)) {
                    return new JsonParser().parse(text);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object javaValue = Context.jsToJava(value, Object.class);
            if (javaValue != null && javaValue != value) {
                return CortexDroneMod.GSON.toJsonTree(javaValue);
            }
        } catch (Throwable ignored) {
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    static Object toJs(JsonElement element, Context cx, Scriptable scope) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsDouble();
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            Object[] values = new Object[array.size()];
            for (int i = 0; i < array.size(); i++) {
                values[i] = toJs(array.get(i), cx, scope);
            }
            return cx.newArray(scope, values);
        }
        JsonObject object = element.getAsJsonObject();
        Scriptable nativeObject = cx.newObject(scope);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            ScriptableObject.putProperty(nativeObject, entry.getKey(), toJs(entry.getValue(), cx, scope));
        }
        return nativeObject;
    }

    /**
     * Maps either a single options object or positional arguments to the schema.
     */
    static JsonObject argumentsToJson(Context cx, Scriptable scope, Object[] args, ToolSpec spec) {
        JsonObject result = new JsonObject();
        if (args == null || args.length == 0) return result;

        if (args.length == 1) {
            JsonElement single = toJson(cx, scope, args[0]);
            if (single != null && single.isJsonObject()) return single.getAsJsonObject();
        }

        List<String> order = spec.argumentOrder();
        for (int i = 0; i < args.length && i < order.size(); i++) {
            if (args[i] == null || args[i] instanceof Undefined) continue;
            result.add(order.get(i), toJson(cx, scope, args[i]));
        }
        return result;
    }

    static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) array.add(new JsonPrimitive(value));
        }
        return array;
    }

    static boolean isArray(Object value) {
        return value instanceof NativeArray;
    }
}
