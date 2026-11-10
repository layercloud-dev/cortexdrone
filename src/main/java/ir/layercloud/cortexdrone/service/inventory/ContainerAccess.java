package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.service.world.BlockSight;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Reach rules for container access.
 *
 * <p>Reading a chest from a distance is fine — {@code scan_chests} and
 * {@code find_item} are allowed to report containers anywhere. <b>Writing</b> is
 * not: a slot transfer must be a physical interaction, so the drone has to be
 * within {@link BlockSight#REACH} with a clear line of sight to the block.</p>
 *
 * <p>This existed as a bug: withdraw/deposit also searched containers around the
 * player, so the drone "stole" items from a chest dozens of blocks away with no
 * travel and no animation.</p>
 */
@UtilityClass
public final class ContainerAccess {
    /** A slot transfer needs the same reach as a block interaction. */
    public static final double REACH = BlockSight.REACH;

    /** Why a container cannot be used, plus where to go instead. */
    @Getter
    public static final class Result {
        private final boolean accessible;
        private final double distance;
        /**
         * -- GETTER --
         * Drone-space point to move to, or
         *  when nothing is visible.
         */
        private final double[] standoff;
        private final String reason;

        private Result(boolean accessible, double distance, double[] standoff, String reason) {
            this.accessible = accessible;
            this.distance = distance;
            this.standoff = standoff;
            this.reason = reason;
        }

    }

    public static Result check(World world, Drone drone, int x, int y, int z) {
        if (world == null || drone == null) {
            return new Result(false, Double.MAX_VALUE, null, "No drone or world.");
        }
        return check(world, drone.posX, BlockSight.eyeY(drone), drone.posZ, x, y, z);
    }

    /**
     * Drone-free form, so pure reach logic can be exercised without an
     * OpenComputers entity (the API is compile-only in unit tests).
     */
    public static Result check(World world, double eyeX, double eyeY, double eyeZ,
                               int x, int y, int z) {
        if (world == null) {
            return new Result(false, Double.MAX_VALUE, null, "No world.");
        }
        BlockSight.Result sight = BlockSight.check(world, eyeX, eyeY, eyeZ, x, y, z);
        if (sight.isVisible() && sight.getDistance() <= REACH) {
            return new Result(true, sight.getDistance(), null, null);
        }
        double[] standoff = BlockSight.visibleStandoff(world,
                eyeX, eyeY - BlockSight.DRONE_EYE_HEIGHT, eyeZ, x, y, z);
        String reason = sight.getDistance() > REACH
                ? "Container is " + round(sight.getDistance()) + " blocks away; reach is "
                + round(REACH) + "."
                : "Line of sight to the container is blocked by " + sight.getOccluderName() + ".";
        return new Result(false, sight.getDistance(), standoff, reason);
    }

    public static Result check(World world, Drone drone, TileEntity tile) {
        if (tile == null) return new Result(false, Double.MAX_VALUE, null, "No container there.");
        return check(world, drone, tile.xCoord, tile.yCoord, tile.zCoord);
    }

    /**
     * Filters a candidate list down to the containers the drone can actually
     * open right now. Used by the "nearest matching chest" transfers so they can
     * never reach through a wall or across the map.
     */
    public static List<TileEntity> reachable(World world, Drone drone, List<TileEntity> candidates) {
        List<TileEntity> usable = new ArrayList<>();
        if (world == null || drone == null || candidates == null) return usable;
        for (TileEntity tile : candidates) {
            if (!(tile instanceof IInventory)) continue;
            if (!check(world, drone, tile).isAccessible()) continue;
            usable.add(tile);
        }
        return usable;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
