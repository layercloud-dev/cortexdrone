package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/** Single-use inventory action: give matching items to a player. */
@RequiredArgsConstructor
public final class GiveToPlayerAction implements Action<InventoryContext> {
    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayerMP player = request.getPlayerName() != null
            ? CortexDroneMod.findPlayer(request.getPlayerName())
            : context.player();
        if (player == null) player = CortexDroneMod.findPlayer(controller.ownerName);

        String filter = request.getFilter() != null ? request.getFilter() : "all";
        int count = request.getCount() > 0 ? request.getCount() : 512;
        if (player != null && player.worldObj == world
            && drone.getDistanceToEntity(player) > 4.2
            && InventoryService.countInventoryItems(drone.mainInventory()) > 0) {
            controller.dropFilter = filter;
            this.manager.setDroneMode(controller, Mode.DROP,
                "RPC deliver_to_player (" + filter + ")");
            context.result(InventoryResult.success("flyingToPlayer", true));
            return;
        }
        int given = this.inventoryService.giveToPlayerInventory(controller, player, filter, count);
        context.result(InventoryResult.success("transferred", given));
    }
}
