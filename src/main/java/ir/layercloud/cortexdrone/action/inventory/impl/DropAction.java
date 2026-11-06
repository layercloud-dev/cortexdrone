package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.inventory.impl.InventoryContext;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/** Single-use inventory action: drop the drone inventory. */
@RequiredArgsConstructor
public final class DropAction implements Action<InventoryContext> {
    private final AIDroneManager manager;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        controller.dropFilter = request.getFilter() != null ? request.getFilter() : "all";
        if (InventoryService.countInventoryItems(drone.mainInventory()) > 0) {
            this.manager.setDroneMode(controller, Mode.DROP, "RPC drop");
        }
        context.result(InventoryResult.success());
    }
}
