package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Reach and line-of-sight rules for the block primitives.
 *
 * <p>Distance alone is not enough: a drone hovering beside a wall is within
 * 4.5 blocks of the blocks behind it. Minecraft reach is a visible-face
 * interaction, so a block can only be broken, placed or activated when a clear
 * voxel ray reaches some point of its box. Non-colliding blocks (air, liquids,
 * plants, torches) are passable and never occlude.</p>
 *
 * <p>The DDA used here is the same Amanatides &amp; Woo traversal as
 * {@link VoxelRaycaster3D}, but the target voxel is always accepted: the ray
 * has to reach the block, not pass through it.</p>
 */
@UtilityClass
public final class BlockSight {
    public static final double REACH = 4.5D;

    /** Vertical offset of the drone's interaction eye above its position. */
    public static final double DRONE_EYE_HEIGHT = 0.1875D;

    private static final int[][] NEIGHBOURS = new int[][]{
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** Eye height for any entity; the drone's own box is 0.75 wide and 0.375 tall. */
    public static double eyeY(Drone drone) {
        if (drone == null) return DRONE_EYE_HEIGHT;
        try {
            return drone.posY + Math.max(DRONE_EYE_HEIGHT, drone.getEyeHeight());
        } catch (Throwable ignored) {
            return drone.posY + DRONE_EYE_HEIGHT;
        }
    }

    /** Two passable voxels: the standoff body voxel and the one above it. */
    public static boolean isOpenForDrone(World world, int x, int y, int z) {
        if (ItemClassifier.isDangerousLiquid(world.getBlock(x, y, z))
                || ItemClassifier.isDangerousLiquid(world.getBlock(x, y + 1, z))) {
            return false;
        }
        return ItemClassifier.isPassableVoxel(world, x, y, z)
                && ItemClassifier.isPassableVoxel(world, x, y + 1, z);
    }

    /** A single visibility query for the server thread. */
    public static Result check(World world, double eyeX, double eyeY, double eyeZ,
                               int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) {
            return Result.hidden(Double.MAX_VALUE, null, null);
        }
        Block block = world.getBlock(x, y, z);
        AxisAlignedBB box = box(world, block, x, y, z);
        double distance = distanceToBox(eyeX, eyeY, eyeZ, box);
        VoxelPassability passable = passable(world);

        Result blocked = null;
        for (double[] point : samplePoints(box)) {
            int[] occluder = firstBlockingVoxel(passable, eyeX, eyeY, eyeZ,
                    point[0], point[1], point[2], x, y, z);
            if (occluder == null) {
                return Result.visible(distance);
            }
            if (blocked == null) {
                blocked = Result.hidden(distance, occluder, describe(world, occluder));
            }
        }
        return blocked != null ? blocked : Result.hidden(distance, null, null);
    }

    /**
     * Finds a point that is both within reach and has a clear line of sight to
     * the target. Prefers the nearest candidate to the drone.
     *
     * <p>This is the shared standoff search used by every primitive that must
     * physically touch a block, so block and container access agree on what
     * "reachable" means.</p>
     *
     * @return {@code {x, y, z}} in drone coordinates, or {@code null}
     */
    public static double[] visibleStandoff(World world, double fromX, double fromY, double fromZ,
                                            int x, int y, int z) {
        if (world == null) return null;
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;
        int[][] directions = new int[][]{
                {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
        };
        for (int[] direction : directions) {
            int ax = x + direction[0];
            int ay = y + direction[1];
            int az = z + direction[2];
            if (!isOpenForDrone(world, ax, ay, az)) continue;
            double[] candidate = standoffPoint(ax, ay, az);
            if (!isClearFrom(world, candidate, x, y, z)) continue;
            double distance = squared(candidate, fromX, fromY, fromZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best != null) return best;

        // Exposed faces can also be reached diagonally (a corner or a ledge).
        for (int radius = 1; radius <= 2 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int ax = x + dx;
                        int ay = y + dy;
                        int az = z + dz;
                        if (!isOpenForDrone(world, ax, ay, az)) continue;
                        double[] candidate = standoffPoint(ax, ay, az);
                        if (!isClearFrom(world, candidate, x, y, z)) continue;
                        double distance = squared(candidate, fromX, fromY, fromZ);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    public static double[] standoffPoint(int x, int y, int z) {
        return new double[]{x + DroneGeometry.CELL_CENTER,
                y + DroneGeometry.AIM_OFFSET, z + DroneGeometry.CELL_CENTER};
    }

    private static boolean isClearFrom(World world, double[] point, int x, int y, int z) {
        Result sight = check(world, point[0], point[1] + DRONE_EYE_HEIGHT, point[2], x, y, z);
        return sight.isVisible() && sight.getDistance() <= REACH;
    }

    private static double squared(double[] point, double x, double y, double z) {
        double dx = point[0] - x;
        double dy = point[1] - y;
        double dz = point[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** True when at least one face of the block is reachable from open air. */

    /**
     * Walks the exact voxel line and returns the first non-passable voxel that
     * is not the target, or {@code null} when the line reaches the target.
     *
     * <p>The starting voxel is never checked: the drone may be clipping a
     * plant or standing in a liquid, which must not make every interaction
     * fail.</p>
     */
    static int[] firstBlockingVoxel(VoxelPassability passable,
                                    double x0, double y0, double z0,
                                    double x1, double y1, double z1,
                                    int targetX, int targetY, int targetZ) {
        int x = MathHelper.floor_double(x0);
        int y = MathHelper.floor_double(y0);
        int z = MathHelper.floor_double(z0);
        if (x == targetX && y == targetY && z == targetZ) return null;

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = Double.compare(dx, 0.0D);
        int stepY = Double.compare(dy, 0.0D);
        int stepZ = Double.compare(dz, 0.0D);

        double tDeltaX = stepX != 0 ? Math.abs(1.0D / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0D / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0D / dz) : Double.MAX_VALUE;
        double tMaxX = stepX > 0 ? (x + 1.0D - x0) * tDeltaX
                : (stepX < 0 ? (x0 - x) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1.0D - y0) * tDeltaY
                : (stepY < 0 ? (y0 - y) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1.0D - z0) * tDeltaZ
                : (stepZ < 0 ? (z0 - z) * tDeltaZ : Double.MAX_VALUE);

        int maxSteps = Math.abs(targetX - x) + Math.abs(targetY - y)
                + Math.abs(targetZ - z) + 4;
        for (int step = 0; step < maxSteps; step++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == targetX && y == targetY && z == targetZ) return null;
            if (!passable.isPassable(x, y, z)) return new int[]{x, y, z};
        }
        return null;
    }

    private static VoxelPassability passable(World world) {
        return (x, y, z) -> ItemClassifier.isPassableVoxel(world, x, y, z);
    }

    private static AxisAlignedBB box(World world, Block block, int x, int y, int z) {
        AxisAlignedBB box = null;
        if (block != null && block != Blocks.air) {
            try {
                block.setBlockBoundsBasedOnState(world, x, y, z);
                box = block.getCollisionBoundingBoxFromPool(world, x, y, z);
            } catch (Throwable ignored) {
            }
        }
        if (box == null) {
            box = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        }
        // Modded blocks occasionally report an empty or inverted box; fall back
        // to the full voxel so the target stays reachable.
        if (box.maxX - box.minX < 1.0E-4D || box.maxY - box.minY < 1.0E-4D
                || box.maxZ - box.minZ < 1.0E-4D) {
            box = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        }
        return box;
    }

    /**
     * Center first (cheapest and the normal hit point), then the six face
     * centers and eight corners. Sampling several points keeps a partially
     * exposed block reachable when a single centre ray grazes a neighbour.
     */
    private static double[][] samplePoints(AxisAlignedBB box) {
        double insetX = Math.min(0.05D, (box.maxX - box.minX) * 0.25D);
        double insetY = Math.min(0.05D, (box.maxY - box.minY) * 0.25D);
        double insetZ = Math.min(0.05D, (box.maxZ - box.minZ) * 0.25D);
        double x0 = box.minX + insetX;
        double x1 = box.maxX - insetX;
        double y0 = box.minY + insetY;
        double y1 = box.maxY - insetY;
        double z0 = box.minZ + insetZ;
        double z1 = box.maxZ - insetZ;
        double cx = (x0 + x1) * 0.5D;
        double cy = (y0 + y1) * 0.5D;
        double cz = (z0 + z1) * 0.5D;
        return new double[][]{
                {cx, cy, cz},
                {x0, cy, cz}, {x1, cy, cz},
                {cx, y0, cz}, {cx, y1, cz},
                {cx, cy, z0}, {cx, cy, z1},
                {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z0}, {x0, y1, z1},
                {x1, y0, z0}, {x1, y0, z1}, {x1, y1, z0}, {x1, y1, z1}
        };
    }

    private static double distanceToBox(double eyeX, double eyeY, double eyeZ,
                                        AxisAlignedBB box) {
        double dx = eyeX < box.minX ? box.minX - eyeX
                : (eyeX > box.maxX ? eyeX - box.maxX : 0.0D);
        double dy = eyeY < box.minY ? box.minY - eyeY
                : (eyeY > box.maxY ? eyeY - box.maxY : 0.0D);
        double dz = eyeZ < box.minZ ? box.minZ - eyeZ
                : (eyeZ > box.maxZ ? eyeZ - box.maxZ : 0.0D);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String describe(World world, int[] position) {
        if (position == null) return "an unknown block";
        Block block = world.getBlock(position[0], position[1], position[2]);
        int meta = world.getBlockMetadata(position[0], position[1], position[2]);
        return ItemClassifier.getBlockDisplayName(block, meta)
                + " at (" + position[0] + "," + position[1] + "," + position[2] + ")";
    }

    /** Result of one line-of-sight query. */
    @Getter
    public static final class Result {
        private final boolean visible;
        private final double distance;
        private final int[] occluder;
        private final String occluderName;

        private Result(boolean visible, double distance, int[] occluder, String occluderName) {
            this.visible = visible;
            this.distance = distance;
            this.occluder = occluder;
            this.occluderName = occluderName;
        }

        static Result visible(double distance) {
            return new Result(true, distance, null, null);
        }

        static Result hidden(double distance, int[] occluder, String occluderName) {
            return new Result(false, distance, occluder, occluderName);
        }

    }

    /** Minimal grid predicate so the DDA can be unit-tested without a World. */
    public interface VoxelPassability {
        boolean isPassable(int x, int y, int z);
    }
}
