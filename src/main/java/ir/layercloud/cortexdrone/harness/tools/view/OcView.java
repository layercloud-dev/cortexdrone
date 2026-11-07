package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.oc.OcMethod;
import ir.layercloud.cortexdrone.service.oc.OcState;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON boundary for the OpenComputers tools.
 *
 * <p>OC returns Java and Scala values; this converts them to plain JSON so the
 * model never sees a wrapper object.</p>
 */
public final class OcView {
    public JsonObject state(OcState state, boolean includeMethods) {
        if (state == null) return ToolJson.error("No OpenComputers state available.");
        if (!state.available()) {
            JsonObject unavailable = ToolJson.error("oc_unavailable",
                    state.error() != null ? state.error() : "OpenComputers is not available.",
                    "This tool needs the OpenComputers mod and a linked drone.", false);
            return unavailable;
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("address", state.address());
        out.addProperty("machine_state", state.machineState());
        out.addProperty("running", state.running());
        out.addProperty("paused", state.paused());
        out.addProperty("components", state.components());
        out.addProperty("max_components", state.maxComponents());
        out.addProperty("energy", state.energy());
        out.addProperty("energy_capacity", state.energyCapacity());
        out.addProperty("tier", state.tier());
        out.addProperty("status_text", state.statusText());
        out.addProperty("light_color", String.format("0x%06X", state.lightColor() & 0xFFFFFF));

        JsonArray names = new JsonArray();
        for (String name : state.componentNames()) names.add(new JsonPrimitive(name));
        out.add("component_names", names);

        if (includeMethods) {
            JsonArray methods = new JsonArray();
            for (OcMethod method : state.methods()) methods.add(method(method));
            out.add("methods", methods);
            out.addProperty("method_count", state.methods().size());
        }
        return out;
    }

    /** One callback descriptor. */
    public JsonObject method(OcMethod method) {
        JsonObject out = new JsonObject();
        out.addProperty("name", method.name());
        out.addProperty("direct", method.direct());
        out.addProperty("getter", method.getter());
        out.addProperty("setter", method.setter());
        if (method.limit() > 0) out.addProperty("limit", method.limit());
        out.addProperty("declared_by", method.owner());
        if (!method.doc().isEmpty()) out.addProperty("doc", method.doc());
        return out;
    }

    /** {@code oc_methods}: searchable callback list. */
    public JsonObject methods(List<OcMethod> methods, String query) {
        JsonArray array = new JsonArray();
        for (OcMethod method : methods) array.add(method(method));
        JsonObject out = ToolJson.ok();
        out.add("methods", array);
        out.addProperty("count", methods.size());
        if (query != null && !query.trim().isEmpty()) out.addProperty("query", query.trim());
        return out;
    }

    /** {@code oc_call} / {@code oc_machine}: unwrap OC tuples into JSON. */
    public JsonObject result(OcResult result) {
        if (result == null) return ToolJson.error("OpenComputers operation returned no result.");
        if (!result.isOk()) {
            JsonObject failure = ToolJson.error("oc_error",
                    result.getError() != null ? result.getError() : "OpenComputers operation failed.",
                    "Check oc_methods for the expected arguments.", true);
            for (Map.Entry<String, Object> entry : result.getFields().entrySet()) {
                failure.add(entry.getKey(), json(entry.getValue()));
            }
            return failure;
        }
        JsonObject out = ToolJson.ok();
        if (result.getOperation() != null) out.addProperty("operation", result.getOperation());
        out.add("value", json(result.getValue()));
        List<Object> values = result.getValues();
        if (values != null && values.size() > 1) {
            JsonArray tuple = new JsonArray();
            for (Object value : values) tuple.add(json(value));
            out.add("values", tuple);
        }
        for (Map.Entry<String, Object> entry : result.getFields().entrySet()) {
            out.add(entry.getKey(), json(entry.getValue()));
        }
        return out;
    }

    /** Converts one OC return value. */
    private JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof CharSequence) return new JsonPrimitive(value.toString());
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Character) return new JsonPrimitive(value.toString());
        if (value instanceof Enum) return new JsonPrimitive(((Enum<?>) value).name());
        if (value instanceof Map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                object.add(String.valueOf(entry.getKey()), json(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection) {
            JsonArray array = new JsonArray();
            for (Object item : (Collection<?>) value) array.add(json(item));
            return array;
        }
        if (value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) array.add(json(Array.get(value, i)));
            return array;
        }
        String text = String.valueOf(value);
        // Item stacks and Scala case classes print useful text; do not leak a
        // class name into the model's context.
        if (text.contains("@")) {
            try {
                return new JsonPrimitive(value.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
        return new JsonPrimitive(text);
    }
}
