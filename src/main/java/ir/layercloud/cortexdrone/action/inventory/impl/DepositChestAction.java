package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Single-use inventory action: deposit the drone inventory into a chest. */
@RequiredArgsConstructor
public final class DepositChestAction implements Action<InventoryContext> {
    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;

        if (request.isHasTarget()) {
            TileEntity tile = world.getTileEntity(
                request.getTargetX(), request.getTargetY(), request.getTargetZ());
            if (tile instanceof IInventory) {
                if (drone.getDistance(
                    request.getTargetX() + 0.5, request.getTargetY() + 0.5,
                    request.getTargetZ() + 0.5) <= 4.2) {
                    int moved = this.inventoryService.depositDroneItemsIntoTile(drone, tile);
                    context.result(InventoryResult.success("deposited", moved));
                    return;
                }
                controller.targetDepositChestPos = new int[]{
                    request.getTargetX(), request.getTargetY(), request.getTargetZ()
                };
                this.manager.setDroneMode(controller, Mode.DEPOSIT,
                    "RPC deposit_chest at (" + request.getTargetX() + ","
                        + request.getTargetY() + "," + request.getTargetZ() + ")");
                context.result(InventoryResult.success("flyingToChest", true));
                return;
            }
        }

        controller.targetDepositChestPos = null;
        if (InventoryService.countInventoryItems(drone.mainInventory()) > 0) {
            this.manager.setDroneMode(controller, Mode.DEPOSIT, "RPC deposit_chest");
            context.result(InventoryResult.success("flyingToChest", true));
        } else {
            context.result(InventoryResult.success("deposited", 0));
        }
    }
}
