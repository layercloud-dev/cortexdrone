package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persistent memory tools: remember facts/preferences and named locations,
 * search/recall them, list them, or forget them.
 *
 * <p>Locations are the important case: a player can say "this is my base" and
 * the entry stores the dimension plus exact coordinates. The store dedupes by
 * {@code kind + name}, so a later "rename/update this base" updates the same
 * entry instead of creating duplicates.</p>
 */
public final class MemoryTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("memory")
                    .description("Persistent memory for player facts and named locations. Use action=remember "
                            + "whenever the player says remember/keep in mind/don't forget, or defines a place "
                            + "('this is my base', 'call this the iron farm'). Locations should include x/y/z "
                            + "and dimension; set use_current_position=true to capture the player/drone position "
                            + "for 'here'/'this place'. Use action=search or list before travelling to a known "
                            + "base/farm/mine. Reusing the same kind+name updates the existing entry. "
                            + "Use action=forget when the player asks to forget something. Never store API keys, "
                            + "passwords or transient task progress.")
                    .parameters(ToolSchema.object()
                            .optionalEnum("action", "remember, search, recall (alias of search), list, or forget.",
                                    "search", "remember", "search", "recall", "list", "forget")
                            .optionalEnum("kind", "Entry kind; inferred from coordinates/location wording when omitted.",
                                    "fact", "fact", "location", "preference", "task", "person")
                            .optionalString("name", "Stable label, e.g. 'base', 'iron farm', 'north mine'.")
                            .optionalString("text", "What to remember, in the player's words.")
                            .optionalString("query", "Search text for action=search/recall.")
                            .optionalString("id", "Memory id or name for action=forget.")
                            .optionalString("tags", "Extra retrieval keywords, comma or space separated.")
                            .optionalNumber("x", "Location X (block coordinate).")
                            .optionalNumber("y", "Location Y (block coordinate).")
                            .optionalNumber("z", "Location Z (block coordinate).")
                            .optionalInt("dimension", "Dimension id: 0 overworld, -1 nether, 1 end.")
                            .optionalBoolean("use_current_position", "Capture the task player's current position "
                                    + "and dimension for this memory.", false)
                            .optionalBoolean("pinned", "Pin the entry so it is always shown in context and "
                                    + "never evicted.", false)
                            .optionalInt("limit", "Maximum results for search/list, 1..50.", 1, 50, 8))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(15_000L)
                    .build()
    );

    private final MemoryStore memory;

    public MemoryTools(AIDroneManager manager, MemoryStore memory) {
        this.memory = memory;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("memory", getClass().getSimpleName(),
                (context, arguments) -> handle(context, arguments));
    }

    private JsonObject handle(ToolContext context, JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString().toLowerCase() : "search";
        String kind = args.has("kind") ? args.get("kind").getAsString() : null;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 8;

        if (action.equals("remember")) {
            String text = args.has("text") ? args.get("text").getAsString() : "";
            String name = args.has("name") ? args.get("name").getAsString() : "";
            if (text.trim().isEmpty() && name.trim().isEmpty()) {
                return ToolJson.error("invalid_arguments", "memory remember requires 'text' or 'name'.",
                        "Pass what the player asked you to remember.", true);
            }
            boolean useCurrent = args.has("use_current_position") && args.get("use_current_position").getAsBoolean();
            boolean hasExplicit = args.has("x") && args.has("y") && args.has("z");
            Position position = null;
            if (useCurrent || (!hasExplicit && "location".equalsIgnoreCase(kind))) {
                position = currentPosition(context);
            }
            boolean hasPosition = hasExplicit || position != null;
            double x = hasExplicit ? args.get("x").getAsDouble() : (position != null ? position.x : 0);
            double y = hasExplicit ? args.get("y").getAsDouble() : (position != null ? position.y : 0);
            double z = hasExplicit ? args.get("z").getAsDouble() : (position != null ? position.z : 0);
            int dimension = args.has("dimension") ? args.get("dimension").getAsInt()
                    : (position != null ? position.dimension : 0);
            if (useCurrent && position == null) {
                return ToolJson.error("no_position", "Could not resolve the current player/drone position.",
                        "Pass x/y/z/dimension explicitly and retry.", true);
            }
            List<String> tags = splitTags(args.has("tags") ? args.get("tags").getAsString() : "");
            boolean pinned = args.has("pinned") && args.get("pinned").getAsBoolean();
            String source = context != null && context.player() != null
                    ? "player:" + context.player().getCommandSenderName() : "agent";
            MemoryEntry entry = this.memory.remember(kind, name, text, hasPosition,
                    x, y, z, dimension, tags, pinned, source);
            if (entry == null) {
                return ToolJson.error("memory_failed", "Memory was empty.", "Provide text or a name.", true);
            }
            JsonObject result = ToolJson.ok();
            result.addProperty("saved", true);
            result.add("memory", toJson(entry));
            return result;
        }

        if (action.equals("forget")) {
            String key = args.has("id") ? args.get("id").getAsString()
                    : (args.has("name") ? args.get("name").getAsString() : "");
            boolean removed = this.memory.forget(key);
            if (!removed) {
                return ToolJson.error("memory_not_found", "No memory matched '" + key + "'.",
                        "Use action=list to see stored ids and names.", true);
            }
            JsonObject result = ToolJson.ok();
            result.addProperty("forgotten", true);
            result.addProperty("key", key);
            return result;
        }

        String query = args.has("query") ? args.get("query").getAsString()
                : (args.has("text") ? args.get("text").getAsString() : "");
        List<MemoryEntry> found = action.equals("list")
                ? this.memory.list(kind, limit) : this.memory.search(query, kind, limit);
        JsonObject result = ToolJson.ok();
        result.addProperty("action", action);
        result.addProperty("count", found.size());
        result.add("memories", toJsonArray(found));
        if (found.isEmpty()) {
            result.addProperty("hint", "No matching memory. Save one with action=remember "
                    + "(kind=location plus x/y/z for places).");
        }
        return result;
    }

    private static JsonArray toJsonArray(List<MemoryEntry> entries) {
        JsonArray array = new JsonArray();
        for (MemoryEntry entry : entries) array.add(toJson(entry));
        return array;
    }

    private static JsonObject toJson(MemoryEntry entry) {
        JsonObject object = CortexDroneMod.GSON.toJsonTree(entry).getAsJsonObject();
        object.addProperty("position", entry.positionSummary());
        return object;
    }

    private static List<String> splitTags(String raw) {
        List<String> tags = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return tags;
        for (String part : raw.split("[,\\s]+")) {
            if (!part.trim().isEmpty()) tags.add(part.trim());
        }
        return tags;
    }

    /** Reads the position on the server thread; the tool itself runs on the agent thread. */
    private static Position currentPosition(ToolContext context) {
        if (context == null || context.manager() == null) return null;
        try {
            JsonObject result = context.manager().callOnMainThread(() -> {
                EntityPlayerMP player = context.player();
                DroneController controller = context.controller();
                if (player == null && controller != null && controller.ownerName != null) {
                    player = CortexDroneMod.findPlayer(controller.ownerName);
                }
                JsonObject object = new JsonObject();
                if (player != null) {
                    object.addProperty("x", player.posX);
                    object.addProperty("y", player.posY);
                    object.addProperty("z", player.posZ);
                    object.addProperty("dimension", player.dimension);
                } else if (controller != null && controller.drone != null) {
                    object.addProperty("x", controller.drone.posX);
                    object.addProperty("y", controller.drone.posY);
                    object.addProperty("z", controller.drone.posZ);
                    object.addProperty("dimension", controller.drone.dimension);
                } else {
                    return null;
                }
                return object;
            }, 3_000L);
            if (result == null || !result.has("x")) return null;
            return new Position(result.get("x").getAsDouble(), result.get("y").getAsDouble(),
                    result.get("z").getAsDouble(), result.get("dimension").getAsInt());
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Position {
        final double x;
        final double y;
        final double z;
        final int dimension;

        Position(double x, double y, double z, int dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }
    }
}
