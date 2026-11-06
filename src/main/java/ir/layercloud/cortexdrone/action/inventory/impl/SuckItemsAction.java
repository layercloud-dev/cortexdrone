package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/** Single-use inventory action: suck nearby dropped items into the drone. */
@RequiredArgsConstructor
public final class SuckItemsAction implements Action<InventoryContext> {
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        Drone drone = context.controller().drone;
        double radius = request.getRadius() > 0
            ? Math.min(16.0, Math.max(1.0, request.getRadius())) : 6.5;
        InventoryService.suckNearbyItems(drone, drone.posX, drone.posY, drone.posZ, radius);
        context.result(InventoryResult.success(
            "totalItems", InventoryService.countInventoryItems(drone.mainInventory())));
    }
}
