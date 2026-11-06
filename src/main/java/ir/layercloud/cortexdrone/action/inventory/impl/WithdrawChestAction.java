package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

/**
 * Single-use inventory action: withdraw matching items from a chest.
 */
@RequiredArgsConstructor
public final class WithdrawChestAction implements Action<InventoryContext> {
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayerMP player = context.player();
        String filter = request.getFilter() != null ? request.getFilter() : "any";
        int count = request.getCount() > 0 ? request.getCount() : 64;
        int moved;

        if (request.isHasSource()) {
            TileEntity tile = world.getTileEntity(
                    request.getSourceX(), request.getSourceY(), request.getSourceZ());
            if (!(tile instanceof IInventory)) {
                context.result(InventoryResult.failure(
                        "No chest found at specified coordinates", "withdrawn", 0));
                return;
            }
            IInventory inventory = (IInventory) tile;
            String resolved = ItemClassifier.resolveHeldFilter(player, filter);
            moved = InventoryService.withdrawFromInventory(inventory, drone.mainInventory(), resolved, count);
            if (moved < count && tile instanceof TileEntityChest) {
                TileEntityChest chest = (TileEntityChest) tile;
                chest.checkForAdjacentChests();
                TileEntityChest[] adjacent = new TileEntityChest[]{
                        chest.adjacentChestZNeg, chest.adjacentChestXPos,
                        chest.adjacentChestXNeg, chest.adjacentChestZPos
                };
                for (TileEntityChest neighbor : adjacent) {
                    if (neighbor != null && moved < count) {
                        moved += InventoryService.withdrawFromInventory(
                                neighbor, drone.mainInventory(), resolved, count - moved);
                    }
                }
            }
        } else {
            moved = this.inventoryService.withdrawMatchingFromNearbyChests(
                    world, controller, player, filter, count);
        }
        context.result(InventoryResult.success("withdrawn", moved));
    }
}
