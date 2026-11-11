package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonObject;

/**
 * One function call requested by the model.
 */
public final class ToolCall {
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments != null ? arguments : "{}";
    }

    public String id() {
        return this.id;
    }

    public String name() {
        return this.name;
    }

    public String arguments() {
        return this.arguments;
    }

    public JsonObject argumentsAsJson() {
        try {
            JsonObject parsed = new com.google.gson.JsonParser().parse(this.arguments).getAsJsonObject();
            return parsed != null ? parsed : new JsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
