package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.InteractionRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Interaction tools: block activation, item use, one melee attack, entity
 * pick/unpick. Each tool performs exactly one interaction.
 */
public final class InteractionRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("interact_block", "Activate exactly one block.")
            .register("use_item", "Use one held item, optionally on a block.")
            .register("attack_entity", "Attack exactly one entity once.")
            .register("pick_entity", "Mount exactly one entity onto the drone.")
            .register("unpick_entity", "Dismount the current rider or an explicit entity.");

    private final InteractionRpcTranslator interaction;

    public InteractionRpcHandler(AIDroneManager manager) {
        this.interaction = new InteractionRpcTranslator(manager.getInteractionManager());
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
        if ("interact_block".equals(m)) return interactBlock(ctrl, player, args);
        if ("use_item".equals(m)) return useItem(ctrl, player, args);
        if ("attack_entity".equals(m)) return attackEntity(ctrl, player, args);
        if ("pick_entity".equals(m) || "unpick_entity".equals(m)) {
            return this.interaction.handle(ctrl, player, m, args);
        }
        return RpcJson.error("Unsupported interaction tool: " + m);
    }

    private JsonObject interactBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!hasCoordinates(args)) return RpcJson.error("interact_block requires x/y/z");
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        double distance = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (distance > ReachSupport.REACH) {
            return ReachSupport.needsMovement(ctrl, x, y, z, "interact", distance);
        }
        return this.interaction.handle(ctrl, player, "interact_block", args);
    }

    private JsonObject useItem(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (hasCoordinates(args)) {
            int x = args.get("x").getAsInt();
            int y = args.get("y").getAsInt();
            int z = args.get("z").getAsInt();
            double distance = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
            if (distance > ReachSupport.REACH) {
                return ReachSupport.needsMovement(ctrl, x, y, z, "use", distance);
            }
        }
        return this.interaction.handle(ctrl, player, "use_item", args);
    }

    /**
     * Melee is its own tool: the request carries {@code entityId}, which the
     * interaction manager routes to {@code AttackEntityAction}.
     */
    private JsonObject attackEntity(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (args == null || !args.has("entityId")) {
            return RpcJson.error("attack_entity requires entityId");
        }
        return this.interaction.handle(ctrl, player, "attack_entity", args);
    }

    private static boolean hasCoordinates(JsonObject args) {
        return args != null && args.has("x") && args.has("y") && args.has("z");
    }
}
