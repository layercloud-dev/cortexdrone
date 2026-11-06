package ir.layercloud.cortexdrone.manager.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.impl.*;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived interaction manager. It only parses requests, creates the matching
 * single-use action, serializes the result and manages context lifetime.
 */
public final class InteractionManager extends ActionManager<InteractionContext, InteractionRequest, InteractionResult> {
    private final InventoryService inventoryService;

    public InteractionManager(InventoryService inventoryService) {
        super("interact", "interact_block", "use_item", "pick_entity", "unpick_entity");
        this.inventoryService = inventoryService;
    }

    @Override
    protected InteractionContext createContext(DroneController controller, EntityPlayerMP player,
                                               String operation) {
        return new InteractionContext(controller, player, operation);
    }

    @Override
    protected Action<InteractionContext> createAction(InteractionContext context, String operation,
                                                      InteractionRequest request) {
        if ("interact_block".equals(operation)) {
            if (!request.isCoordinates()) {
                return ignored -> context.result(InteractionResult.failure(
                        "Missing x/y/z for interact_block", "activated", false));
            }
            return new InteractBlockAction(this.inventoryService, request);
        }
        if ("use_item".equals(operation)) {
            return request.hasEntity()
                    ? new AttackEntityAction(request)
                    : new UseItemAction(request);
        }
        if ("pick_entity".equals(operation)) {
            if (!request.hasEntity()) {
                return ignored -> context.result(InteractionResult.failure(
                        "pick_entity requires entityId", "picked", false));
            }
            return new PickEntityAction(request);
        }
        if ("unpick_entity".equals(operation)) return new UnpickEntityAction(request);
        throw new IllegalArgumentException("Unsupported interaction action: " + operation);
    }

    @Override
    protected InteractionResult result(InteractionContext context) {
        return context.result();
    }
}
