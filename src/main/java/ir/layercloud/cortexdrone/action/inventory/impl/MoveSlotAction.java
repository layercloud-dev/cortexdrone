package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Single-use inventory action: move items between drone, player and containers.
 */
@RequiredArgsConstructor
public final class MoveSlotAction implements Action<InventoryContext> {
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayerMP player = context.player() != null
                ? context.player() : CortexDroneMod.findPlayer(controller.ownerName);

        String fromType = request.getFromType() != null
                ? request.getFromType() : "drone";
        String toType = request.getToType() != null
                ? request.getToType() : "player";
        int count = request.getCount() > 0 ? request.getCount() : 64;
        String itemFilter = request.getItem() != null ? request.getItem() : request.getFilter();

        TileEntity sourceTile = request.isHasSource()
                ? world.getTileEntity(request.getSourceX(), request.getSourceY(), request.getSourceZ())
                : this.inventoryService.findNearestInventoryTile(
                world, drone.posX, drone.posY, drone.posZ, 24);
        TileEntity targetTile = request.isHasTarget()
                ? world.getTileEntity(request.getTargetX(), request.getTargetY(), request.getTargetZ())
                : this.inventoryService.findNearestInventoryWithSpace(
                world, drone.posX, drone.posY, drone.posZ, 24,
                sourceTile instanceof IInventory ? (IInventory) sourceTile : drone.mainInventory());

        boolean fromContainer = isContainer(fromType);
        boolean toContainer = isContainer(toType);
        IInventory source = "player".equals(fromType) && player != null ? player.inventory
                : (fromContainer && sourceTile instanceof IInventory
                ? (IInventory) sourceTile : drone.mainInventory());
        IInventory target = "player".equals(toType) && player != null ? player.inventory
                : (toContainer && targetTile instanceof IInventory
                ? (IInventory) targetTile : drone.mainInventory());

        int moved = this.inventoryService.moveInventorySlot(
                source, request.getFromSlot(), itemFilter,
                target, request.getToSlot(), count, player);
        context.result(InventoryResult.success("moved", moved));
    }

    private static boolean isContainer(String type) {
        return "chest".equals(type) || "crate".equals(type)
                || "container".equals(type) || "furnace".equals(type);
    }
}
