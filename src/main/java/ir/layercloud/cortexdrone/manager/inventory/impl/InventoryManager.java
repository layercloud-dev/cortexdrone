package ir.layercloud.cortexdrone.manager.inventory.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.action.inventory.impl.*;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived inventory action manager. Every inventory tool is its own
 * operation: there is no generic {@code transfer(op)} switch. Read-only
 * inventory inspection stays in InventoryQueryTools.
 */
public final class InventoryManager extends ActionManager<InventoryContext, InventoryRequest, InventoryResult> {
    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final DroneLogger logger;

    public InventoryManager(AIDroneManager manager, InventoryService inventoryService,
                            DroneLogger logger) {
        super("inventory", "move_item", "give_to_player", "take_from_player",
                "withdraw_chest", "deposit_chest", "drop_items", "suck_items", "craft");
        this.manager = manager;
        this.inventoryService = inventoryService;
        this.logger = logger;
    }

    @Override
    protected InventoryContext createContext(DroneController controller, EntityPlayerMP player,
                                             String operation) {
        return new InventoryContext(controller, player, operation);
    }

    @Override
    protected Action<InventoryContext> createAction(InventoryContext context, String operation,
                                                    InventoryRequest request) {
        switch (operation) {
            case "craft":
                return new CraftAction(this.inventoryService.getCraftingService(),
                        this.logger, request);
            case "take_from_player":
                return new TakeFromPlayerAction(this.inventoryService, request);
            case "give_to_player":
                return new GiveToPlayerAction(this.manager.getNavigationManager(), this.inventoryService, request);
            case "move_item":
                return new MoveSlotAction(this.inventoryService, request);
            case "withdraw_chest":
                return new WithdrawChestAction(this.inventoryService, request);
            case "deposit_chest":
                return new DepositChestAction(this.manager.getNavigationManager(), this.inventoryService, request);
            case "drop_items":
                return new DropAction(this.manager.getNavigationManager(), this.inventoryService, request);
            case "suck_items":
                return new SuckItemsAction(request);
            default:
                return ignored -> context.result(InventoryResult.failure(
                        "Unsupported inventory action: " + operation));
        }
    }

    @Override
    protected InventoryResult result(InventoryContext context) {
        return context.result();
    }
}
