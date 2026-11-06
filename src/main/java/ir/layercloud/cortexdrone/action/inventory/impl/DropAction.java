package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Single-use inventory action: hand the drone inventory to its owner, or drop it
 * on the ground when the owner is offline. No DROP mode is involved.
 */
@RequiredArgsConstructor
public final class DropAction implements Action<InventoryContext> {
    private static final double REACH = 3.5;

    private final AIDroneManager manager;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (InventoryService.countInventoryItems(drone.mainInventory()) <= 0) {
            context.result(InventoryResult.success("dropped", 0));
            return;
        }
        String filter = filter();
        context.filter(filter);
        EntityPlayerMP owner = CortexDroneMod.findPlayer(controller.ownerName);
        if (owner == null || owner.worldObj != world) {
            int dropped = InventoryService.dropAllDroneItemsAt(drone, world, drone.posX, drone.posY, drone.posZ);
            context.movedCount(dropped);
            context.result(InventoryResult.success("dropped", dropped));
            return;
        }
        if (drone.getDistanceToEntity(owner) <= REACH) {
            deliver(context, controller, drone, world, owner, filter);
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
        World world = drone.worldObj;
        EntityPlayerMP owner = CortexDroneMod.findPlayer(controller.ownerName);
        if (owner == null || owner.worldObj != world) {
            int dropped = InventoryService.dropAllDroneItemsAt(drone, world, drone.posX, drone.posY, drone.posZ);
            context.movedCount(dropped);
            context.result(InventoryResult.success("dropped", dropped));
            context.complete();
            return;
        }
        if (drone.getDistanceToEntity(owner) <= REACH) {
            String filter = context.filter() != null ? context.filter() : filter();
            deliver(context, controller, drone, world, owner, filter);
            return;
        }
        this.manager.getNavigationManager().flyToward(
                controller, owner.posX, owner.posY + 1.25, owner.posZ, "Deliver to player");
        drone.statusText_$eq("Delivering\nItems");
    }

    private void deliver(InventoryContext context, DroneController controller, Drone drone,
                         World world, EntityPlayerMP owner, String filter) {
        int delivered = this.manager.getInventoryService()
                .giveToPlayerInventory(controller, owner, filter, 9999);
        if (isAll(filter)) {
            delivered += InventoryService.dropAllDroneItemsAt(
                    drone, world, owner.posX, owner.posY + 0.5, owner.posZ);
        }
        world.playSoundAtEntity(drone, "random.pop", 0.6f, 1.2f);
        context.movedCount(delivered);
        context.result(InventoryResult.success("delivered", delivered));
        context.complete();
    }

    private String filter() {
        return this.request.getFilter() != null ? this.request.getFilter() : "all";
    }

    private static boolean isAll(String filter) {
        return "all".equalsIgnoreCase(filter) || "any".equalsIgnoreCase(filter) || "*".equals(filter);
    }
}
