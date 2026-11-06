package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.InventoryRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Inventory mutation tools: one explicit operation per tool plus {@code craft}.
 * Tool names map straight to manager operations; no {@code op} field is injected.
 */
public final class InventoryActionRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("move_item", "Move filtered items between inventories/slots.")
            .register("give_to_player", "Give drone items to a player, flying into reach first.")
            .register("take_from_player", "Take filtered items from a player.")
            .register("withdraw_chest", "Withdraw filtered items from a chest/container.")
            .register("deposit_chest", "Deposit the drone inventory into a chest/container.")
            .register("drop_items", "Deliver the drone inventory to the owner, or drop it.")
            .register("suck_items", "Vacuum nearby dropped items into the drone.")
            .register("craft", "Craft from drone-inventory ingredients only.");

    /** The eight tools this handler owns; used by the surface test. */
    public static ToolRegistry tools() {
        return TOOLS;
    }

    private final InventoryRpcTranslator inventory;

    public InventoryActionRpcHandler(AIDroneManager manager) {
        this.inventory = new InventoryRpcTranslator(manager.getInventoryManager());
    }

    @Override
    public ToolRegistry toolRegistry() {
        return TOOLS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (!TOOLS.contains(m)) {
            return RpcJson.error("Unsupported inventory tool: " + m);
        }
        return this.inventory.handle(ctrl, player, m, args);
    }
}
