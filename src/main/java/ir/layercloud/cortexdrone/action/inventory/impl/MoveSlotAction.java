package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.ContainerGuard;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.service.inventory.ContainerAnimation;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
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
        int count = request.getCount() > 0 ? request.getCount() : InventoryLimits.DEFAULT_TRANSFER_COUNT;
        String itemFilter = request.getItem() != null ? request.getItem() : request.getFilter();

        boolean fromContainer = isContainer(fromType);
        boolean toContainer = isContainer(toType);

        // When no container is named, prefer one the drone can actually open.
        TileEntity sourceTile = request.isHasSource()
                ? world.getTileEntity(request.getSourceX(), request.getSourceY(), request.getSourceZ())
                : (fromContainer ? nearestReachableSource(world, drone) : null);
        TileEntity targetTile = request.isHasTarget()
                ? world.getTileEntity(request.getTargetX(), request.getTargetY(), request.getTargetZ())
                : (toContainer ? nearestReachableTarget(world, drone, sourceTile) : null);
        IInventory source = "player".equals(fromType) && player != null ? player.inventory
                : (fromContainer && sourceTile instanceof IInventory
                ? (IInventory) sourceTile : drone.mainInventory());
        IInventory target = "player".equals(toType) && player != null ? player.inventory
                : (toContainer && targetTile instanceof IInventory
                ? (IInventory) targetTile : drone.mainInventory());

        // Container slots are physical: never touch one out of reach.
        if (fromContainer && sourceTile != null) {
            InventoryResult blocked = ContainerGuard.blocked(world, drone, sourceTile, "take from");
            if (blocked != null) {
                context.result(blocked);
                return;
            }
        }
        if (toContainer && targetTile != null) {
            InventoryResult blocked = ContainerGuard.blocked(world, drone, targetTile, "put into");
            if (blocked != null) {
                context.result(blocked);
                return;
            }
        }

        TileEntity animated = fromContainer ? sourceTile : (toContainer ? targetTile : null);
        final IInventory src = source;
        final IInventory dst = target;
        final EntityPlayerMP syncPlayer = player;
        int moved = ContainerAnimation.withOpen(world, drone, animated, () ->
                this.inventoryService.moveInventorySlot(src, request.getFromSlot(), itemFilter,
                        dst, request.getToSlot(), count, syncPlayer));
        context.result(InventoryResult.success("moved", moved));
    }

    /** Nearest container in reach, used when the caller did not name one. */
    private TileEntity nearestReachableSource(World world, Drone drone) {
        java.util.List<TileEntity> tiles = this.reachableContainers(world, drone);
        return tiles.isEmpty() ? null : tiles.get(0);
    }

    /** Nearest reachable container that still has free space. */
    private TileEntity nearestReachableTarget(World world, Drone drone, TileEntity source) {
        for (TileEntity tile : this.reachableContainers(world, drone)) {
            if (tile == source || !(tile instanceof IInventory)) continue;
            if (InventoryService.isInventoryFull((IInventory) tile)) continue;
            return tile;
        }
        return null;
    }

    private java.util.List<TileEntity> reachableContainers(World world, Drone drone) {
        return ir.layercloud.cortexdrone.service.inventory.ContainerAccess.reachable(
                world, drone,
                this.inventoryService.findAllInventoryTiles(
                        world, drone.posX, drone.posY, drone.posZ, 24));
    }

    private static boolean isContainer(String type) {
        return "chest".equals(type) || "crate".equals(type)
                || "container".equals(type) || "furnace".equals(type);
    }
}
