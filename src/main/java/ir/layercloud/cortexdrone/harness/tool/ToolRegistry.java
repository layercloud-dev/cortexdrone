package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the harness tool surface.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>the catalog declares every {@link ToolSpec} (name, description, schema,
 *       affinity, timeout);</li>
 *   <li>domain providers bind a handler to each declared name;</li>
 *   <li>{@code ToolInvoker} and the prompt/OpenAI serializer read the same
 *       registry.</li>
 * </ol>
 *
 * <p>There is no hard-coded tool list in the prompt, the JavaScript SDK or the
 * agent loop: adding a tool to a provider automatically exposes it to the
 * model, the JS runtime and the HTTP endpoint.</p>
 */
public final class ToolRegistry {
    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    public void declare(ToolSpec spec) {
        if (spec == null) return;
        String key = normalize(spec.getName());
        Binding existing = this.bindings.get(key);
        if (existing != null) {
            throw new IllegalStateException("Duplicate tool declaration: " + key);
        }
        this.bindings.put(key, new Binding(key, spec));
    }

    public void declareAll(Collection<ToolSpec> specs) {
        if (specs == null) return;
        for (ToolSpec spec : specs) declare(spec);
    }

    public void bind(String name, String owner, ToolHandler handler) {
        String key = normalize(name);
        Binding binding = this.bindings.get(key);
        if (binding == null) {
            throw new IllegalStateException("Cannot bind undeclared tool: " + key);
        }
        if (binding.handler != null) {
            throw new IllegalStateException("Tool already bound: " + key + " (owner " + binding.owner + ")");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null for " + key);
        }
        binding.handler = handler;
        binding.owner = owner != null ? owner : "unknown";
    }

    public boolean contains(String name) {
        return this.bindings.containsKey(normalize(name));
    }

    /** Declared spec, bound or not. */
    public ToolSpec spec(String name) {
        Binding binding = this.bindings.get(normalize(name));
        return binding != null ? binding.spec : null;
    }

    /** Fully bound tool or {@code null} when the name is unknown/unbound. */
    public Tool find(String name) {
        Binding binding = this.bindings.get(normalize(name));
        if (binding == null || binding.handler == null) return null;
        return new Tool(binding.spec, binding.handler, binding.owner);
    }

    public List<Tool> tools() {
        List<Tool> out = new ArrayList<>();
        for (Binding binding : this.bindings.values()) {
            if (binding.handler != null) out.add(new Tool(binding.spec, binding.handler, binding.owner));
        }
        return Collections.unmodifiableList(out);
    }

    public List<ToolSpec> specs() {
        List<ToolSpec> out = new ArrayList<>();
        for (Binding binding : this.bindings.values()) out.add(binding.spec);
        return Collections.unmodifiableList(out);
    }

    /** Public model tools only, in declaration order. */
    public List<ToolSpec> modelTools() {
        List<ToolSpec> out = new ArrayList<>();
        for (Binding binding : this.bindings.values()) {
            if (!binding.spec.isInternal()) out.add(binding.spec);
        }
        return Collections.unmodifiableList(out);
    }

    /** OpenAI-compatible {@code tools} array for the chat completion request. */
    public JsonArray toOpenAiTools() {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : modelTools()) array.add(spec.toOpenAiTool());
        return array;
    }

    /** Compact JSON catalog injected into the context message. */
    public JsonArray toPromptCatalog() {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : modelTools()) array.add(spec.toPromptEntry());
        return array;
    }

    public String[] names() {
        return this.bindings.keySet().toArray(new String[0]);
    }

    public int declaredCount() {
        return this.bindings.size();
    }

    public int boundCount() {
        int count = 0;
        for (Binding binding : this.bindings.values()) {
            if (binding.handler != null) count++;
        }
        return count;
    }

    /** Fails fast at startup when a declared tool was never bound. */
    public void requireAllBound() {
        List<String> unbound = new ArrayList<>();
        for (Binding binding : this.bindings.values()) {
            if (binding.handler == null) unbound.add(binding.name);
        }
        if (!unbound.isEmpty()) {
            throw new IllegalStateException("Unbound tools: " + unbound);
        }
    }

    private static String normalize(String name) {
        return name != null ? name.trim().toLowerCase() : "";
    }

    private static final class Binding {
        final String name;
        final ToolSpec spec;
        ToolHandler handler;
        String owner;

        Binding(String name, ToolSpec spec) {
            this.name = name;
            this.spec = spec;
        }
    }
}
