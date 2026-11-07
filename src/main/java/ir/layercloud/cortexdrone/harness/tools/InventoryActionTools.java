package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tools.view.InventoryView;

import java.util.Arrays;
import java.util.List;

/**
 * Inventory mutation: one explicit transfer/craft operation per call.
 * Transfers may fly the drone into reach and then wait for completion.
 */
public final class InventoryActionTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("move_item")
                    .description("Move filtered items between the drone, the owner and container slots. "
                            + "Set from/to to 'drone', 'player' or 'chest'; give chest coordinates via "
                            + "from_x/from_y/from_z or to_x/to_y/to_z. Optional from_slot/to_slot pin exact "
                            + "slots. count=0 means as many as possible. A container slot is a physical "
                            + "interaction: if it is out of reach the result is needs_movement + standoff, "
                            + "never a long-range transfer.")
                    .parameters(ToolSchema.object()
                            .requiredString("from", "Source: drone, player or chest.")
                            .requiredString("to", "Destination: drone, player or chest.")
                            .optionalString("filter", "Item name, registry name, ore dictionary tag or 'all'.")
                            .optionalInt("count", "Maximum items to move; 0 = as many as possible.", 0, 2304, 0)
                            .optionalInt("from_slot", "Exact source slot, or -1 for any.", -1, 255, -1)
                            .optionalInt("to_slot", "Exact destination slot, or -1 for any.", -1, 255, -1)
                            .optionalInt("from_x", "Source chest X (when from=chest).")
                            .optionalInt("from_y", "Source chest Y.")
                            .optionalInt("from_z", "Source chest Z.")
                            .optionalInt("to_x", "Destination chest X (when to=chest).")
                            .optionalInt("to_y", "Destination chest Y.")
                            .optionalInt("to_z", "Destination chest Z."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(90_000L)
                    .build(),
            ToolSpec.builder("give_to_player")
                    .description("Give filtered drone items to a player. The drone flies into reach first. "
                            + "Use filter='all' to hand over the entire cargo; count caps the number of items.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all")
                            .optionalInt("count", "Maximum items to give; 0 = as many as fit.", 0, 2304, 0)
                            .optionalString("player", "Player name; defaults to the owner/task player."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(120_000L)
                    .build(),
            ToolSpec.builder("take_from_player")
                    .description("Take filtered items from a player's inventory into the drone. The drone "
                            + "flies into reach first. Use this to obtain materials the player is holding.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all")
                            .optionalInt("count", "Maximum items to take; 0 = as many as fit.", 0, 2304, 0)
                            .optionalString("player", "Player name; defaults to the owner/task player."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(120_000L)
                    .build(),
            ToolSpec.builder("withdraw_chest")
                    .description("Withdraw filtered items from one chest/container. Pass x/y/z to target a "
                            + "specific container, or omit them to use the nearest container the drone can "
                            + "actually open. Only containers within reach with line of sight are used, and "
                            + "each is opened and closed so the lid/sound plays. Out of reach returns "
                            + "needs_movement + standoff.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all")
                            .optionalInt("count", "Maximum items to withdraw; 0 = as many as fit.", 0, 2304, 0)
                            .optionalInt("x", "Chest X (optional).")
                            .optionalInt("y", "Chest Y (optional).")
                            .optionalInt("z", "Chest Z (optional)"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(120_000L)
                    .build(),
            ToolSpec.builder("deposit_chest")
                    .description("Fly to a chest/container and deposit the drone inventory. Pass x/y/z for a "
                            + "specific container, or omit to use the nearest container with space. The "
                            + "transfer only happens once the drone is within reach, and the container is "
                            + "opened and closed so its animation plays.")
                    .parameters(ToolSchema.object()
                            .optionalInt("x", "Chest X (optional).")
                            .optionalInt("y", "Chest Y (optional).")
                            .optionalInt("z", "Chest Z (optional)"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(120_000L)
                    .build(),
            ToolSpec.builder("drop_items")
                    .description("Deliver the drone's inventory to the owner, or drop it in place when the "
                            + "owner is offline. Optional filter narrows what is dropped.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(120_000L)
                    .build(),
            ToolSpec.builder("suck_items")
                    .description("Vacuum nearby dropped item entities into the drone inventory. radius is in "
                            + "blocks; items that do not fit remain on the ground.")
                    .parameters(ToolSchema.object()
                            .optionalNumber("radius", "Pickup radius in blocks, 1..16.", 1, 16, 4))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("craft")
                    .description("Craft a vanilla crafting-table recipe from the DRONE inventory only. "
                            + "Missing ingredients fail the call; pull/gather them first. count is the number of "
                            + "result items requested (the action crafts as many complete recipes as possible).")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Result item name/registry/ore tag.", "planks")
                            .optionalInt("count", "Number of result items wanted.", 1, 2304, 1))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(60_000L)
                    .build()
    );

    private final InventoryView inventory;

    public InventoryActionTools(AIDroneManager manager) {
        this.inventory = new InventoryView(manager.getInventoryManager());
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    /**
     * A successful container transfer is a real visit: remember where it is and
     * which trail got the drone there, so the next trip can retrace it.
     */
    private static void rememberVisit(ToolContext context, String name, JsonObject args,
                                      JsonObject result) {
        if (result == null || !result.has("ok") || !result.get("ok").getAsBoolean()) return;
        if (result.has("needsMovement") && result.get("needsMovement").getAsBoolean()) return;
        if (context == null || context.controller() == null
                || context.controller().drone == null || context.controller().drone.worldObj == null) return;
        String kind = "chest";
        int[] coords = null;
        if (args.has("to_x") && args.has("to_y") && args.has("to_z")) {
            coords = new int[]{args.get("to_x").getAsInt(), args.get("to_y").getAsInt(), args.get("to_z").getAsInt()};
        } else if (args.has("from_x") && args.has("from_y") && args.has("from_z")) {
            coords = new int[]{args.get("from_x").getAsInt(), args.get("from_y").getAsInt(), args.get("from_z").getAsInt()};
        }
        if (coords == null) return;
        PlaceMemory.rememberContainer(context, context.controller(),
                context.controller().drone.worldObj, coords[0], coords[1], coords[2], kind);
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : SPECS) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> {
                        JsonObject result = this.inventory.handle(
                                context.controller(), context.player(), name, arguments);
                        rememberVisit(context, name, arguments, result);
                        return result;
                    });
        }
    }
}
