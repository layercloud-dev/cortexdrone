package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.inventory.CraftResult;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;

/**
 * Single-use inventory action: craft matching items.
 */
@RequiredArgsConstructor
public final class CraftAction implements Action<InventoryContext> {
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;
        String filter = request.getFilter() != null ? request.getFilter() : "planks";
        int count = request.getCount() > 0 ? request.getCount() : 1;
        CraftResult result = this.inventoryService.craftItem(
                world, controller, context.player(), filter, count);
        if (!result.isOk()) {
            context.result(InventoryResult.failure(result.getError(),
                    "crafted", 0, "item", result.getItem()));
        } else {
            context.result(InventoryResult.success(
                    "crafted", result.getCrafted(), "item", result.getItem()));
        }
    }
}
