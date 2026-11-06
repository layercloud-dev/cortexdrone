package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.BuildingRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Block tools: {@code break_block} and {@code place_block}. Each call performs
 * exactly one physical block operation; the model composes loops itself.
 */
public final class BlockRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("break_block", "Break exactly one block at x/y/z.")
            .register("place_block", "Place exactly one block from the drone inventory.");

    private final BuildingRpcTranslator building;

    public BlockRpcHandler(AIDroneManager manager) {
        this.building = new BuildingRpcTranslator(manager.getBuildingManager());
    }

    /** The tools this handler owns, available without a live manager. */
    public static ToolRegistry tools() {
        return TOOLS;
    }

    @Override
    public ToolRegistry toolRegistry() {
        return TOOLS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (!hasCoordinates(args)) return RpcJson.error(m + " requires x/y/z");
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        World world = ctrl.drone.worldObj;
        double distance = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (distance > ReachSupport.REACH) {
            return ReachSupport.needsMovement(ctrl, x, y, z,
                    "break_block".equals(m) ? "break" : "place", distance);
        }
        if ("break_block".equals(m) && !ItemClassifier.isMineableBlock(world, x, y, z)) {
            return RpcJson.error("Block at (" + x + "," + y + "," + z + ") is not mineable/air");
        }
        if ("place_block".equals(m) && !ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            return RpcJson.error("Target (" + x + "," + y + "," + z + ") is already occupied/protected");
        }
        return this.building.handle(ctrl, player, m, args);
    }

    private static boolean hasCoordinates(JsonObject args) {
        return args != null && args.has("x") && args.has("y") && args.has("z");
    }
}
