package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable declaration of one tool: name, model-facing description, JSON
 * schema, thread affinity and timeout. A spec is intentionally handler-free so
 * the whole surface can be inspected and unit-tested without Minecraft.
 */
public final class ToolSpec {
    private final String name;
    private final String description;
    private final JsonObject parameters;
    private final ToolAffinity affinity;
    private final boolean internal;
    private final long timeoutMs;

    private ToolSpec(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = builder.parameters;
        this.affinity = builder.affinity;
        this.internal = builder.internal;
        this.timeoutMs = builder.timeoutMs;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public JsonObject getParameters() {
        return this.parameters;
    }

    public ToolAffinity getAffinity() {
        return this.affinity;
    }

    public boolean isInternal() {
        return this.internal;
    }

    public long getTimeoutMs() {
        return this.timeoutMs;
    }

    /** Ordered property names, used to map positional JavaScript arguments. */
    public List<String> argumentOrder() {
        JsonObject properties = this.parameters.getAsJsonObject("properties");
        List<String> order = new ArrayList<>();
        if (properties == null) return order;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : properties.entrySet()) {
            order.add(entry.getKey());
        }
        return Collections.unmodifiableList(order);
    }

    /** OpenAI {@code tools[]} entry for this spec (internal tools are skipped there). */
    public JsonObject toOpenAiTool() {
        JsonObject function = new JsonObject();
        function.addProperty("name", this.name);
        function.addProperty("description", this.description);
        function.add("parameters", this.parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    /** Compact one-line description used in the generated prompt tool map. */
    public JsonObject toPromptEntry() {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", this.name);
        entry.addProperty("description", firstSentence(this.description));
        entry.addProperty("affinity", this.affinity.name().toLowerCase());
        JsonArray required = this.parameters.getAsJsonArray("required");
        if (required != null && required.size() > 0) {
            entry.add("required", required);
        }
        return entry;
    }

    private static String firstSentence(String text) {
        if (text == null || text.isEmpty()) return "";
        int dot = text.indexOf(". ");
        String first = dot > 20 ? text.substring(0, dot + 1) : text;
        return first.length() > 220 ? first.substring(0, 217) + "..." : first;
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private JsonObject parameters = ToolSchema.object().build();
        private ToolAffinity affinity = ToolAffinity.MAIN;
        private boolean internal = false;
        private long timeoutMs = 8_000L;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Tool name must not be blank");
            }
            this.name = name.trim().toLowerCase();
        }

        public Builder description(String value) {
            this.description = value != null ? value : "";
            return this;
        }

        public Builder parameters(ToolSchema schema) {
            this.parameters = schema.build();
            return this;
        }

        public Builder parameters(JsonObject schema) {
            this.parameters = schema;
            return this;
        }

        public Builder affinity(ToolAffinity value) {
            this.affinity = value;
            return this;
        }

        /** Internal tools exist for the JS transport layer and are hidden from the model. */
        public Builder internal() {
            this.internal = true;
            return this;
        }

        public Builder timeoutMs(long value) {
            this.timeoutMs = value;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(this);
        }
    }
}
