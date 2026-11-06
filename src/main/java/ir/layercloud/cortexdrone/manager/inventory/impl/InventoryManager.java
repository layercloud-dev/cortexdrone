package ir.layercloud.cortexdrone.manager.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.action.inventory.impl.*;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived inventory action manager. It parses transfer/craft requests,
 * creates one single-use action, serializes the result and manages the context.
 * Read-only inventory inspection stays in InventoryRpcHandler.
 */
public final class InventoryManager extends ActionManager<InventoryContext, InventoryRequest, InventoryResult> {
    private final AIDroneManager manager;
    private final InventoryService inventoryService;

    public InventoryManager(AIDroneManager manager, InventoryService inventoryService) {
        super("inventory", "transfer", "craft");
        this.manager = manager;
        this.inventoryService = inventoryService;
    }

    @Override
    protected InventoryContext createContext(DroneController controller, EntityPlayerMP player,
                                             String operation) {
        return new InventoryContext(controller, player, operation);
    }

    @Override
    protected Action<InventoryContext> createAction(InventoryContext context, String operation,
                                                    InventoryRequest request) {
        if ("craft".equals(operation)) {
            return new CraftAction(this.inventoryService, request);
        }
        String op = request.getOperation();
        if ("take".equals(op)) return new TakeFromPlayerAction(this.inventoryService, request);
        if ("give".equals(op)) return new GiveToPlayerAction(this.manager, this.inventoryService, request);
        if ("move".equals(op)) return new MoveSlotAction(this.inventoryService, request);
        if ("withdraw".equals(op)) return new WithdrawChestAction(this.inventoryService, request);
        if ("deposit".equals(op)) return new DepositChestAction(this.manager, this.inventoryService, request);
        if ("drop".equals(op)) return new DropAction(this.manager, request);
        if ("suck".equals(op)) return new SuckItemsAction(request);
        return ignored -> context.result(InventoryResult.failure(
                "Unknown transfer op: " + op));
    }

    @Override
    protected InventoryResult result(InventoryContext context) {
        return context.result();
    }
}
