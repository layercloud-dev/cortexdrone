package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.action.navigation.FlightController;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.ContainerAccess;
import ir.layercloud.cortexdrone.service.inventory.ContainerAnimation;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Single-use inventory action: fly to a chest and deposit the drone inventory.
 *
 * <p>The action retains itself while it travels, so there is no DEPOSIT state on
 * the drone. When it finishes, the context completes and the drone is idle.</p>
 */
@RequiredArgsConstructor
public final class DepositChestAction implements Action<InventoryContext> {
    private final FlightController flight;
    private final InventoryService inventoryService;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        World world = drone.worldObj;

        TileEntity chest = resolveChest(context, world, drone);
        if (chest == null) {
            context.result(InventoryResult.failure("No chest with free space within 28m"));
            return;
        }
        if (inReach(drone, world, chest)) {
            deposit(context, drone, world, chest);
            return;
        }
        context.result(InventoryResult.success("flyingToChest", true));
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
        int[] pos = context.chestPos();
        if (pos == null) {
            context.result(InventoryResult.failure("No chest target"));
            context.complete();
            return;
        }
        TileEntity chest = world.getTileEntity(pos[0], pos[1], pos[2]);
        if (!(chest instanceof IInventory)) {
            context.result(InventoryResult.failure("Chest disappeared at " + pos[0] + "," + pos[1] + "," + pos[2]));
            context.complete();
            return;
        }
        if (inReach(drone, world, chest)) {
            deposit(context, drone, world, chest);
            return;
        }
        double[] standoff = FlightPathfinder.findBlockStandoff(
                world, drone, chest.xCoord, chest.yCoord, chest.zCoord);
        double cx = standoff != null ? standoff[0] : chest.xCoord + DroneGeometry.CELL_CENTER;
        double cy = standoff != null ? standoff[1] : chest.yCoord + 1.2;
        double cz = standoff != null ? standoff[2] : chest.zCoord + DroneGeometry.CELL_CENTER;
        this.flight.flyToward(controller, cx, cy, cz, "Deposit flight");
        drone.statusText_$eq("Depositing\nChest");
    }

    private TileEntity resolveChest(InventoryContext context, World world, Drone drone) {
        if (this.request.isHasTarget()) {
            TileEntity explicit = world.getTileEntity(
                    this.request.getTargetX(), this.request.getTargetY(), this.request.getTargetZ());
            if (explicit instanceof IInventory) {
                context.chestPos(new int[]{
                        this.request.getTargetX(), this.request.getTargetY(), this.request.getTargetZ()});
                return explicit;
            }
            context.result(InventoryResult.failure("No inventory at "
                    + this.request.getTargetX() + "," + this.request.getTargetY() + "," + this.request.getTargetZ()));
            return null;
        }
        TileEntity nearest = this.inventoryService.findNearestInventoryWithSpace(
                world, drone.posX, drone.posY, drone.posZ, 28, drone.mainInventory());
        if (nearest == null) return null;
        context.chestPos(new int[]{nearest.xCoord, nearest.yCoord, nearest.zCoord});
        return nearest;
    }

    private static boolean inReach(Drone drone, World world, TileEntity chest) {
        // Same rule as every other container: reach plus a clear line of sight.
        return ContainerAccess.check(world, drone, chest).isAccessible();
    }

    private void deposit(InventoryContext context, Drone drone, World world, TileEntity chest) {
        // Open through the container's real hook so the lid/feedback animates.
        int moved = ContainerAnimation.withOpen(world, drone, chest,
                () -> this.inventoryService.depositDroneItemsIntoTile(drone, chest));
        context.movedCount(moved);
        context.result(InventoryResult.success("deposited", moved,
                "chest", chest.xCoord + "," + chest.yCoord + "," + chest.zCoord));
        context.complete();
    }
}
