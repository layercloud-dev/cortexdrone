package ir.layercloud.cortexdrone.action.inventory;

import ir.layercloud.cortexdrone.service.inventory.ContainerAccess;
import li.cil.oc.common.entity.Drone;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Reach guard shared by every inventory action that touches a container.
 *
 * <p>A slot transfer is a physical interaction. When the container is out of
 * reach the action returns {@code needsMovement} plus a visible standoff, which
 * the JavaScript binding navigates to before retrying. A direct model call gets
 * the same envelope and a clear error, never a silent long-range transfer.</p>
 */
@SuppressWarnings("unused")
public final class ContainerGuard {
    private ContainerGuard() {
    }

    /**
     * @return {@code null} when the container can be opened from here, otherwise
     * the typed failure envelope.
     */
    public static InventoryResult blocked(World world, Drone drone, TileEntity tile, String action) {
        if (tile == null) {
            return InventoryResult.failure("No container at that position.", "action", action);
        }
        ContainerAccess.Result access = ContainerAccess.check(world, drone, tile);
        if (access.isAccessible()) return null;

        String target = tile.xCoord + "," + tile.yCoord + "," + tile.zCoord;
        double[] standoff = access.getStandoff();
        if (standoff == null) {
            return InventoryResult.failure(
                    "Cannot " + action + " container at " + target + ": " + access.getReason(),
                    "out_of_reach", true,
                    "action", action,
                    "target", target,
                    "distance", round(access.getDistance()),
                    "hint", "Move closer with goto. There is no visible standoff, so the container "
                            + "is enclosed; expose a face before retrying.");
        }
        return InventoryResult.builder()
                .ok(true)
                .field("needsMovement", true)
                .field("action", action)
                .field("target", target)
                .field("distance", round(access.getDistance()))
                .field("standoff", point(standoff))
                .field("hint", "Move to standoff, then retry " + action + ".")
                .build();
    }

    /**
     * Guard for a container that has already been resolved to an inventory.
     */
    public static InventoryResult blocked(World world, Drone drone, IInventory inventory, String action) {
        return inventory instanceof TileEntity
                ? blocked(world, drone, (TileEntity) inventory, action)
                : null;
    }

    private static com.google.gson.JsonObject point(double[] xyz) {
        com.google.gson.JsonObject point = new com.google.gson.JsonObject();
        point.addProperty("x", round(xyz[0]));
        point.addProperty("y", round(xyz[1]));
        point.addProperty("z", round(xyz[2]));
        return point;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
