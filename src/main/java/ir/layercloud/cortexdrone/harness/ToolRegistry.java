package ir.layercloud.cortexdrone.harness;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered set of RPC tools owned by one domain handler.
 *
 * <p>Every handler declares its own registry; {@code RpcDispatcher} merges the
 * registries of all registered handlers and rejects duplicate names. There is no
 * central catalog and no operation router: one name is one tool, owned by
 * exactly one handler.</p>
 */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public ToolRegistry register(String name, String description) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (this.tools.containsKey(key)) {
            throw new IllegalStateException("Duplicate tool: " + key);
        }
        this.tools.put(key, new Tool(key, description != null ? description : ""));
        return this;
    }

    public boolean contains(String name) {
        return this.tools.containsKey(normalize(name));
    }

    /** Registered tool or {@code null} when the name is unknown. */
    public Tool find(String name) {
        return this.tools.get(normalize(name));
    }

    public List<Tool> tools() {
        return Collections.unmodifiableList(new ArrayList<Tool>(this.tools.values()));
    }

    public String[] names() {
        return this.tools.keySet().toArray(new String[0]);
    }

    public int size() {
        return this.tools.size();
    }

    private static String normalize(String name) {
        return name != null ? name.trim().toLowerCase() : "";
    }

    /**
     * One registered tool. Immutable so a caller cannot mutate the surface.
     */
    @Value
    public static class Tool {
        String name;
        String description;
    }
}
