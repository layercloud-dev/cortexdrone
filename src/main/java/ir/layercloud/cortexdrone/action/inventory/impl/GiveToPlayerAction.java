package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Single-use inventory action: give matching items to a player, flying to them
 * first when they are out of reach. No DROP mode is involved.
 */
@RequiredArgsConstructor
public final class GiveToPlayerAction implements Action<InventoryContext> {
    private static final double REACH = 3.5;

    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        EntityPlayerMP player = resolvePlayer(context, controller);
        if (player == null) {
            context.result(InventoryResult.failure("Player not found"));
            return;
        }
        String filter = filter();
        context.filter(filter);
        if (inReach(drone, player)) {
            int given = this.inventoryService.giveToPlayerInventory(controller, player, filter, count());
            context.movedCount(given);
            context.result(InventoryResult.success("transferred", given));
            return;
        }
        context.result(InventoryResult.success("flyingToPlayer", true));
        context.retain();
    }

    @Override
    public void tick(InventoryContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            context.complete();
            return;
        }
        Drone drone = controller.drone;
        EntityPlayerMP player = resolvePlayer(context, controller);
        if (player == null || player.worldObj != drone.worldObj) {
            context.result(InventoryResult.failure("Player offline"));
            context.complete();
            return;
        }
        if (inReach(drone, player)) {
            String filter = context.filter() != null ? context.filter() : filter();
            int given = this.inventoryService.giveToPlayerInventory(controller, player, filter, count());
            context.movedCount(given);
            context.result(InventoryResult.success("transferred", given));
            context.complete();
            return;
        }
        this.manager.getNavigationManager().flyToward(
                controller, player.posX, player.posY + 1.25, player.posZ, "Deliver to player");
        drone.statusText_$eq("Delivering\nItems");
    }

    private EntityPlayerMP resolvePlayer(InventoryContext context, DroneController controller) {
        EntityPlayerMP player = this.request.getPlayerName() != null
                ? CortexDroneMod.findPlayer(this.request.getPlayerName())
                : context.player();
        return player != null ? player : CortexDroneMod.findPlayer(controller.ownerName);
    }

    private static boolean inReach(Drone drone, EntityPlayerMP player) {
        World world = drone.worldObj;
        return player.worldObj == world && drone.getDistanceToEntity(player) <= REACH;
    }

    private String filter() {
        return this.request.getFilter() != null ? this.request.getFilter() : "all";
    }

    private int count() {
        return this.request.getCount() > 0 ? this.request.getCount() : 512;
    }
}
