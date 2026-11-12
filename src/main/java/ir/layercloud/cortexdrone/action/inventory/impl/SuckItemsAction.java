package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/**
 * Single-use inventory action: suck nearby dropped items into the drone.
 */
@RequiredArgsConstructor
public final class SuckItemsAction implements Action<InventoryContext> {
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        Drone drone = context.controller().drone;
        if (drone.mainInventory() == null || drone.mainInventory().getSizeInventory() == 0) {
            context.result(InventoryResult.failure(
                    "The drone inventory has no slots, so it cannot pick anything up.",
                    "hint", "Install an inventory upgrade on the drone before collecting drops."));
            return;
        }
        double radius = request.getRadius() > 0
                ? InventoryLimits.clampSuckRadius(request.getRadius())
                : InventoryLimits.DEFAULT_SUCK_RADIUS;
        String filter = request.getFilter() != null ? request.getFilter() : "all";
        int pickedUp = InventoryService.suckNearbyItems(
                drone, drone.posX, drone.posY, drone.posZ, radius, filter);
        int after = InventoryService.countInventoryItems(drone.mainInventory());
        context.result(InventoryResult.success(
                "pickedUp", pickedUp,
                "totalItems", after,
                "radius", radius,
                "filter", filter));
    }
}
