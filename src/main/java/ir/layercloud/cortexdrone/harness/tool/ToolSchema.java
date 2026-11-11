package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Fluent builder for a JSON Schema object that describes one tool's arguments.
 *
 * <p>The schema is sent verbatim to the model as the OpenAI function
 * {@code parameters} object. Property insertion order is preserved so the
 * JavaScript runtime can map positional call arguments deterministically.</p>
 */
@SuppressWarnings("unused")
public final class ToolSchema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    private ToolSchema() {
    }

    public static ToolSchema object() {
        return new ToolSchema();
    }

    public ToolSchema requiredInt(String name, String description) {
        return add(name, primitive("integer", description), true, null);
    }

    public ToolSchema optionalInt(String name, String description) {
        return add(name, primitive("integer", description), false, null);
    }

    public ToolSchema optionalInt(String name, String description, Integer defaultValue) {
        return add(name, primitive("integer", description), false, defaultValue);
    }

    public ToolSchema requiredInt(String name, String description, int min, int max) {
        JsonObject schema = primitive("integer", description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return add(name, schema, true, null);
    }

    public ToolSchema optionalInt(String name, String description, int min, int max, Integer defaultValue) {
        JsonObject schema = primitive("integer", description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return add(name, schema, false, defaultValue);
    }

    public ToolSchema requiredNumber(String name, String description) {
        return add(name, primitive("number", description), true, null);
    }

    public ToolSchema optionalNumber(String name, String description) {
        return add(name, primitive("number", description), false, null);
    }

    public ToolSchema optionalNumber(String name, String description, Number defaultValue) {
        return add(name, primitive("number", description), false, defaultValue);
    }

    public ToolSchema optionalNumber(String name, String description, double min, double max, Number defaultValue) {
        JsonObject schema = primitive("number", description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return add(name, schema, false, defaultValue);
    }

    public ToolSchema requiredBoolean(String name, String description) {
        return add(name, primitive("boolean", description), true, null);
    }

    public ToolSchema optionalBoolean(String name, String description) {
        return add(name, primitive("boolean", description), false, null);
    }

    public ToolSchema optionalBoolean(String name, String description, Boolean defaultValue) {
        return add(name, primitive("boolean", description), false, defaultValue);
    }

    public ToolSchema requiredString(String name, String description) {
        return add(name, primitive("string", description), true, null);
    }

    public ToolSchema optionalString(String name, String description) {
        return add(name, primitive("string", description), false, null);
    }

    public ToolSchema optionalString(String name, String description, String defaultValue) {
        return add(name, primitive("string", description), false, defaultValue);
    }

    public ToolSchema requiredEnum(String name, String description, String... values) {
        return add(name, enumSchema(description, values), true, null);
    }

    public ToolSchema optionalEnum(String name, String description, String defaultValue, String... values) {
        return add(name, enumSchema(description, values), false, defaultValue);
    }

    public ToolSchema optionalStringArray(String name, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        schema.add("items", items);
        return add(name, schema, false, null);
    }

    /**
     * Adds a free-form object property (for {@code {x,y,z}} style arguments).
     */
    public ToolSchema optionalObject(String name, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        schema.addProperty("additionalProperties", true);
        return add(name, schema, false, null);
    }

    public ToolSchema requiredObject(String name, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        schema.addProperty("additionalProperties", true);
        return add(name, schema, true, null);
    }

    /**
     * Escape hatch for a hand-written property schema.
     */
    public ToolSchema property(String name, JsonObject schema, boolean isRequired) {
        return add(name, schema, isRequired, null);
    }

    public JsonObject build() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", this.properties);
        if (this.required.size() > 0) {
            root.add("required", this.required);
        }
        root.addProperty("additionalProperties", false);
        return root;
    }

    private ToolSchema add(String name, JsonObject schema, boolean isRequired, Object defaultValue) {
        if (defaultValue != null) {
            if (defaultValue instanceof Number) {
                schema.add("default", new JsonPrimitive((Number) defaultValue));
            } else if (defaultValue instanceof Boolean) {
                schema.add("default", new JsonPrimitive((Boolean) defaultValue));
            } else {
                schema.add("default", new JsonPrimitive(String.valueOf(defaultValue)));
            }
        }
        this.properties.add(name, schema);
        if (isRequired) this.required.add(new JsonPrimitive(name));
        return this;
    }

    private static JsonObject primitive(String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description != null ? description : "");
        return schema;
    }

    private static JsonObject enumSchema(String description, String... values) {
        JsonObject schema = primitive("string", description);
        JsonArray arr = new JsonArray();
        for (String value : values) arr.add(new JsonPrimitive(value));
        schema.add("enum", arr);
        return schema;
    }
}
