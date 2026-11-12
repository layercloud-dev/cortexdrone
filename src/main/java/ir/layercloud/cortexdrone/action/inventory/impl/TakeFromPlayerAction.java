package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.TransferAnimation;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * Single-use inventory action: take matching items from a player.
 */
@RequiredArgsConstructor
public final class TakeFromPlayerAction implements Action<InventoryContext> {
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        EntityPlayerMP player = request.getPlayerName() != null
                ? CortexDroneMod.findPlayer(request.getPlayerName())
                : context.player();
        if (player == null && controller != null) {
            player = CortexDroneMod.findPlayer(controller.ownerName);
        }
        String filter = request.getFilter() != null ? request.getFilter() : "any";
        int count = request.getCount() > 0 ? request.getCount() : InventoryLimits.DEFAULT_TRANSFER_COUNT;
        int moved = this.inventoryService.takeFromPlayerInventory(controller, player, filter, count);
        if (moved > 0 && controller != null && controller.drone != null) {
            TransferAnimation.move(controller.drone.worldObj, controller.drone,
                    player.posX, player.posY + 1.1D, player.posZ,
                    controller.drone.posX, controller.drone.posY + 0.4D,
                    controller.drone.posZ, firstStack(controller));
        }
        context.result(InventoryResult.success("transferred", moved));
    }

    private static ItemStack firstStack(DroneController controller) {
        if (controller == null || controller.drone == null) return null;
        IInventory inv = controller.drone.mainInventory();
        if (inv == null) return null;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0 && st.getItem() != null) return st;
        }
        return null;
    }
}
