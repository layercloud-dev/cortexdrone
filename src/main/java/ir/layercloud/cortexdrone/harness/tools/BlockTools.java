package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolTimeouts;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.BuildingView;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * One physical block operation per call. Loops and multi-block plans belong in
 * `execute`, never in a hidden queue.
 */
public final class BlockTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("break_block")
                    .description("Break exactly one block at x/y/z with the drone's held tool. No filter, "
                            + "count, neighbour propagation or queue. Drops go to the drone inventory when "
                            + "there is room. If the drone is out of reach the result contains "
                            + "needs_movement + standoff; move there and retry. If the block is protected or "
                            + "not mineable the result says so.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Block X coordinate.")
                            .requiredInt("y", "Block Y coordinate.")
                            .requiredInt("z", "Block Z coordinate.")
                            .optionalInt("max_wait_ms", "Upper bound for the movement retry, "
                                    + NavigationLimits.MIN_WAIT_MS + ".." + NavigationLimits.MAX_WAIT_MS + ".",
                                    NavigationLimits.MIN_WAIT_MS, NavigationLimits.MAX_WAIT_MS,
                                    NavigationLimits.DEFAULT_WAIT_MS))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.BLOCK_ACTION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build(),
            ToolSpec.builder("place_block")
                    .description("Place exactly one block from the DRONE inventory at x/y/z. The target "
                            + "must be replaceable (air, water, grass...). Never pulls from chests or the "
                            + "player: gather the block first. 'block' accepts an item name, registry name, "
                            + "ore dictionary tag or 'any'. If out of reach, returns needs_movement+standoff.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Target block X.")
                            .requiredInt("y", "Target block Y.")
                            .requiredInt("z", "Target block Z.")
                            .optionalString("block", "Block/item name or ore dictionary tag; 'any' picks any "
                                    + "placeable block in the drone inventory.", "any")
                            .optionalInt("meta", "Block metadata, -1 = any/auto.", -1, 15, -1)
                            .optionalInt("max_wait_ms", "Upper bound for the movement retry.",
                                    NavigationLimits.MIN_WAIT_MS, NavigationLimits.MAX_WAIT_MS,
                                    NavigationLimits.DEFAULT_WAIT_MS))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.BLOCK_ACTION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build(),
            ToolSpec.builder("replace_block")
                    .description("Atomically replace one block at x/y/z with a block from the DRONE "
                            + "inventory. Checks the replacement first, removes the old block and places "
                            + "the new one in the same call; if placement fails the original block is "
                            + "restored, so it never leaves a hole. If the block already matches 'block' "
                            + "(and meta) nothing is changed and alreadyMatching=true. Refuses protected "
                            + "blocks, chests, machines and unreachable targets. Use this for floors, walls "
                            + "and ceilings instead of break_block followed by place_block.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Target block X.")
                            .requiredInt("y", "Target block Y.")
                            .requiredInt("z", "Target block Z.")
                            .optionalString("block", "Replacement block/item name, registry name or ore "
                                    + "dictionary tag; 'any' picks any placeable block in the drone "
                                    + "inventory.", "any")
                            .optionalInt("meta", "Block metadata, -1 = auto.", -1, 15, -1)
                            .optionalInt("max_wait_ms", "Upper bound for the movement retry.",
                                    NavigationLimits.MIN_WAIT_MS, NavigationLimits.MAX_WAIT_MS,
                                    NavigationLimits.DEFAULT_WAIT_MS))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.BLOCK_ACTION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build(),
            ToolSpec.builder("repair_trail")
                    .description("Put back the blocks the drone had to break to reach earlier targets. "
                            + "Navigation avoids damage and only tunnels when no detour exists, but those "
                            + "breaks are journalled. This closes the tunnel again, restoring block state "
                            + "and tile entities. Slots that are no longer air are left untouched.")
                    .parameters(ToolSchema.object())
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.ACTION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build()
    );

    private final BuildingView building;

    public BlockTools(AIDroneManager manager) {
        this.building = new BuildingView(manager.getBuildingManager());
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("break_block", getClass().getSimpleName(),
                (context, arguments) -> handle(context.controller(), context.player(), "break_block", arguments));
        registry.bind("place_block", getClass().getSimpleName(),
                (context, arguments) -> handle(context.controller(), context.player(), "place_block", arguments));
        registry.bind("replace_block", getClass().getSimpleName(),
                (context, arguments) -> handle(context.controller(), context.player(), "replace_block", arguments));
        registry.bind("repair_trail", getClass().getSimpleName(),
                (context, arguments) -> this.building.handle(
                        context.controller(), context.player(), "repair_trail", arguments));
    }

    private JsonObject handle(DroneController controller, EntityPlayerMP player,
                              String method, JsonObject args) {
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        World world = controller.drone.worldObj;
        // Reach is distance AND line of sight: a block behind a wall is not
        // interactable even when its centre is within 4.5 blocks.
        JsonObject unreachable = ReachSupport.unreachable(controller, x, y, z,
                "break_block".equals(method) ? "break"
                        : ("replace_block".equals(method) ? "replace" : "place"));
        if (unreachable != null) return unreachable;
        if ("break_block".equals(method) && !ItemClassifier.isMineableBlock(world, x, y, z)) {
            return ToolJson.error("not_mineable", "Block at (" + x + "," + y + "," + z
                    + ") is air, unbreakable or protected.", "Inspect it with get_block and pick another target.", true);
        }
        if ("replace_block".equals(method) && !ItemClassifier.isMineableBlock(world, x, y, z)) {
            return ToolJson.error("not_replaceable", "Block at (" + x + "," + y + "," + z
                    + ") is air, unbreakable or protected. Replacement never removes chests, machines, "
                    + "bedrock or air.", "Choose a normal floor/wall/ceiling block.", true);
        }
        if ("place_block".equals(method) && !ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            return ToolJson.error("occupied", "Target (" + x + "," + y + "," + z
                    + ") is already occupied.", "Choose a replaceable target position.", true);
        }
        return this.building.handle(controller, player, method, args);
    }
}
