package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

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
        int count = request.getCount() > 0 ? request.getCount() : 512;
        int moved = this.inventoryService.takeFromPlayerInventory(controller, player, filter, count);
        context.result(InventoryResult.success("transferred", moved));
    }
}
