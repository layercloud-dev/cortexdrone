package ir.layercloud.cortexdrone.harness;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered registry of the master tools exposed to Code Mode.
 *
 * <p>The registry is the single source of truth for the tool surface. A tool is
 * registered once with a short description; {@link #master()} builds the
 * canonical set. It is dependency-free on purpose so the surface can be
 * asserted by tests without loading Minecraft classes.</p>
 *
 * <p>Registration is first-wins: registering the same name twice keeps the
 * original entry, which makes duplicate wiring harmless instead of silently
 * replacing a tool.</p>
 */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    /**
     * Canonical master tool surface. Mining, harvesting, building and farming
     * are deliberately absent: the model composes them from these primitives.
     */
    public static ToolRegistry master() {
        return new ToolRegistry()
                .register("state", "Live drone status, task and navigation snapshot.")
                .register("inspect", "One read-only world, inventory, machine or doc query.")
                .register("goto", "Fly to a coordinate; resolves on arrival or failure.")
                .register("stop", "Cancel navigation and hover in place.")
                .register("break_block", "Break exactly one block immediately.")
                .register("place_block", "Place exactly one block from the drone inventory.")
                .register("interact_block", "Activate exactly one block.")
                .register("use_item", "Use one item, optionally on a block or entity.")
                .register("pick_entity", "Mount exactly one entity onto the drone.")
                .register("unpick_entity", "Dismount the current rider or an explicit entity.")
                .register("transfer", "One inventory transfer operation.")
                .register("craft", "Craft from drone-inventory ingredients only.")
                .register("visuals", "One visual, audio or chat effect.")
                .register("skills", "Search, list or save reusable skills.")
                .register("cancel", "Cancel the current task, loops and retained actions.");
    }

    public ToolRegistry register(String name, String description) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        this.tools.putIfAbsent(key, new Tool(key, description != null ? description : ""));
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
