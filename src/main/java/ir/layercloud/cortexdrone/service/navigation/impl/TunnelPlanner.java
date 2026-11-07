package ir.layercloud.cortexdrone.service.navigation.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.world.BlockSight;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D;
import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Finds the next block that stops a straight tunnel toward a goal.
 *
 * <p>Used by automatic navigation recovery: when no no-dig route exists the
 * navigator can clear one voxel at a time instead of handing the failure back
 * to the model. Safety rules live in {@link #safeToDig}: protected tile
 * entities, liquids, the player's own space and the world floor are never
 * touched.</p>
 */
@UtilityClass
public final class TunnelPlanner {
    /** Do not start a tunnel when the first obstruction is farther than this. */
    public static final double MAX_LOOKAHEAD = 8.0D;

    /** Never mine closer than this to the player, in blocks. */
    private static final double PLAYER_CLEARANCE = 2.5D;

    /**
     * First mineable voxel on the straight line from the drone's eye to the
     * goal, or {@code null} when the line is already open.
     */
    public static int[] nextObstruction(World world, Drone drone,
                                        double goalX, double goalY, double goalZ) {
        if (world == null || drone == null) return null;
        double eyeY = BlockSight.eyeY(drone);
        double dx = goalX - drone.posX;
        double dy = goalY - eyeY;
        double dz = goalZ - drone.posZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.35D) return null;
        JsonObject hit = VoxelRaycaster3D.raycastSolidBlock(world,
                drone.posX, eyeY, drone.posZ,
                dx / length, dy / length, dz / length,
                Math.min(MAX_LOOKAHEAD, length));
        if (hit == null) return null;
        if (!hit.has("x") || !hit.has("y") || !hit.has("z")) return null;
        return new int[]{hit.get("x").getAsInt(), hit.get("y").getAsInt(), hit.get("z").getAsInt()};
    }

    /**
     * True when breaking this voxel is both possible and acceptable for
     * automatic navigation.
     */
    public static boolean safeToDig(World world, EntityPlayerMP player,
                                    int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) return false;
        if (y <= 2 || y >= 254) return false;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return false;
        Block block = world.getBlock(x, y, z);
        if (block == null) return false;
        Material material = block.getMaterial();
        if (material != null && (material == Material.water || material == Material.lava)) {
            return false;
        }
        if (block.getBlockHardness(world, x, y, z) > 50.0F) return false;
        if (player != null) {
            double cx = x + 0.5D;
            double cy = y + 0.5D;
            double cz = z + 0.5D;
            double dx = player.posX - cx;
            double dy = player.posY + player.getEyeHeight() - cy;
            double dz = player.posZ - cz;
            if (dx * dx + dy * dy + dz * dz < PLAYER_CLEARANCE * PLAYER_CLEARANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deterministic vertical step toward a goal that is above or below the
     * drone.
     *
     * <p>Straight-line tunnelling fails underground because the first
     * obstruction is the floor the drone is hovering over, and no flight path
     * exists to its standoff. A player just mines down. This returns the voxel
     * to clear so the drone can descend (or ascend) one step and keep going.</p>
     *
     * @return {@code {x, y, z}} to break, or {@code null}
     */
    public static int[] nextVertical(World world, Drone drone, EntityPlayerMP player,
                                     double goalX, double goalY, double goalZ) {
        if (world == null || drone == null) return null;
        int bx = (int) Math.floor(drone.posX);
        int bz = (int) Math.floor(drone.posZ);
        int feetY = (int) Math.floor(drone.posY);

        if (goalY < drone.posY - 0.75D) {
            int[] below = {bx, feetY - 1, bz};
            if (canDescend(world, player, below)) return below;
            int stepX = (int) Math.signum(goalX - drone.posX);
            int stepZ = (int) Math.signum(goalZ - drone.posZ);
            int[][] diagonals = {{stepX, stepZ}, {stepX, 0}, {0, stepZ},
                    {-stepX, 0}, {0, -stepZ}};
            for (int[] diagonal : diagonals) {
                if (diagonal[0] == 0 && diagonal[1] == 0) continue;
                int[] candidate = {bx + diagonal[0], feetY - 1, bz + diagonal[1]};
                if (canDescend(world, player, candidate)) return candidate;
            }
            return null;
        }

        if (goalY > drone.posY + 1.25D) {
            int[] above = {bx, feetY + 2, bz};
            if (canAscend(world, player, above)) return above;
            int stepX = (int) Math.signum(goalX - drone.posX);
            int stepZ = (int) Math.signum(goalZ - drone.posZ);
            int[][] diagonals = {{stepX, stepZ}, {stepX, 0}, {0, stepZ}};
            for (int[] diagonal : diagonals) {
                if (diagonal[0] == 0 && diagonal[1] == 0) continue;
                int[] candidate = {bx + diagonal[0], feetY + 2, bz + diagonal[1]};
                if (canAscend(world, player, candidate)) return candidate;
            }
        }
        return null;
    }

    /** Below-step is usable when the voxel is solid and the drone's space stays clear. */
    private static boolean canDescend(World world, EntityPlayerMP player, int[] target) {
        if (!world.blockExists(target[0], target[1], target[2])) return false;
        if (world.isAirBlock(target[0], target[1], target[2])) return false;
        if (!safeToDig(world, player, target[0], target[1], target[2])) return false;
        // The drone must fit above the freshly dug voxel.
        return ItemClassifier.isPassableVoxel(world, target[0], target[1] + 1, target[2])
                && ItemClassifier.isPassableVoxel(world, target[0], target[1] + 2, target[2]);
    }

    /** Above-step is usable when the ceiling is solid and the space beyond is open. */
    private static boolean canAscend(World world, EntityPlayerMP player, int[] target) {
        if (!world.blockExists(target[0], target[1], target[2])) return false;
        if (world.isAirBlock(target[0], target[1], target[2])) return false;
        if (!safeToDig(world, player, target[0], target[1], target[2])) return false;
        return ItemClassifier.isPassableVoxel(world, target[0], target[1] + 1, target[2])
                && ItemClassifier.isPassableVoxel(world, target[0], target[1] + 2, target[2]);
    }

    /** True when the drone can break the voxel without moving. */
    public static boolean inReach(Drone drone, int x, int y, int z) {
        if (drone == null) return false;
        BlockSight.Result sight = BlockSight.check(drone.worldObj,
                drone.posX, BlockSight.eyeY(drone), drone.posZ, x, y, z);
        return sight.isVisible() && sight.getDistance() <= BlockSight.REACH;
    }
}
