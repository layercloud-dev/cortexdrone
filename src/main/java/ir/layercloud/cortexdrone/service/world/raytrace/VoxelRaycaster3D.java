package ir.layercloud.cortexdrone.service.world.raytrace;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Amanatides & Woo (1987) "A Fast Voxel Traversal Algorithm for Ray Tracing" (3D DDA).
 * Visits every voxel intersected by a 3D ray in exact order without oversampling or skipping corners,
 * and tracks the exact entry face normal (faceX, faceY, faceZ).
 */
@UtilityClass
public final class VoxelRaycaster3D {

    /**
     * Exact 3D DDA line-of-sight passability check using ChunkCacheContext (for Theta* pathfinding).
     */
    public static boolean isLineClear(ChunkCacheContext bsi, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = MathHelper.floor_double(x0);
        int y = MathHelper.floor_double(y0);
        int z = MathHelper.floor_double(z0);
        int endX = MathHelper.floor_double(x1);
        int endY = MathHelper.floor_double(y1);
        int endZ = MathHelper.floor_double(z1);

        if (x == endX && y == endY && z == endZ) {
            return bsi.isPassable(x, y, z);
        }

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX = stepX > 0 ? (x + 1.0 - x0) * tDeltaX : (stepX < 0 ? (x0 - x) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1.0 - y0) * tDeltaY : (stepY < 0 ? (y0 - y) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1.0 - z0) * tDeltaZ : (stepZ < 0 ? (z0 - z) * tDeltaZ : Double.MAX_VALUE);

        int maxSteps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 3;
        for (int step = 0; step < maxSteps; step++) {
            if (!bsi.isPassable(x, y, z)) {
                return false;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
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
        }
        return bsi.isPassable(endX, endY, endZ);
    }

    /**
     * Fat-ray line-of-sight: runs the exact DDA along the center ray AND six offset rays so a
     * smoothed flight segment can never graze through a solid corner of the drone's 0.72-block
     * corridor. Use this for Theta* shortcutting - the center-line check alone let the drone clip
     * deepslate/obsidian walls when A* said "COMPLETE" and velocity was then hard-blocked.
     */
    public static boolean isFatLineClear(ChunkCacheContext bsi, double x0, double y0, double z0, double x1, double y1, double z1) {
        // OC drones are 0.75 x 0.375. The old +/-0.30 probe left the outer
        // 0.075 of each side unchecked, so a smoothed segment could graze a
        // wall and the executor then hard-blocked. Cover the actual half-width
        // plus a small margin, and probe the top of the body as well.
        double[][] offsets = new double[][]{{0.39, 0.0, 0.0}, {-0.39, 0.0, 0.0}, {0.0, 0.0, 0.39}, {0.0, 0.0, -0.39}, {0.0, 0.37, 0.0}};
        for (double[] o : offsets) {
            if (!isLineClear(bsi, x0 + o[0], y0 + o[1], z0 + o[2], x1 + o[0], y1 + o[1], z1 + o[2])) {
                return false;
            }
        }
        return isLineClear(bsi, x0, y0, z0, x1, y1, z1);
    }

    /**
     * Fat-ray line-of-sight directly against World (used for the final waypoint shortcut).
     */
    public static boolean isFatLineClear(World world, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] offsets = new double[][]{{0.39, 0.0, 0.0}, {-0.39, 0.0, 0.0}, {0.0, 0.0, 0.39}, {0.0, 0.0, -0.39}, {0.0, 0.37, 0.0}};
        for (double[] o : offsets) {
            if (!isLineClear(world, x0 + o[0], y0 + o[1], z0 + o[2], x1 + o[0], y1 + o[1], z1 + o[2])) {
                return false;
            }
        }
        return isLineClear(world, x0, y0, z0, x1, y1, z1);
    }

    /**
     * Exact 3D DDA line-of-sight passability check directly against World.
     */
    public static boolean isLineClear(World world, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = MathHelper.floor_double(x0);
        int y = MathHelper.floor_double(y0);
        int z = MathHelper.floor_double(z0);
        int endX = MathHelper.floor_double(x1);
        int endY = MathHelper.floor_double(y1);
        int endZ = MathHelper.floor_double(z1);

        if (x == endX && y == endY && z == endZ) {
            return isPassableGated(world, x, y, z);
        }

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX = stepX > 0 ? (x + 1.0 - x0) * tDeltaX : (stepX < 0 ? (x0 - x) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1.0 - y0) * tDeltaY : (stepY < 0 ? (y0 - y) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1.0 - z0) * tDeltaZ : (stepZ < 0 ? (z0 - z) * tDeltaZ : Double.MAX_VALUE);

        int maxSteps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 3;
        for (int step = 0; step < maxSteps; step++) {
            if (!isPassableGated(world, x, y, z)) {
                return false;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
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
        }
        return isPassableGated(world, endX, endY, endZ);
    }

    /**
     * Direct-world raycasts must not silently generate chunks while the player
     * policy is closed. The chunk manager loads/claims only when allowed;
     * otherwise the voxel is treated as solid.
     */
    private static boolean isPassableGated(World world, int x, int y, int z) {
        if (!world.isRemote && !ChunkAccess.ensureBlockLoaded(world, x, z)) return false;
        return ItemClassifier.isPassableVoxel(world, x, y, z);
    }

    /**
     * Exact 3D DDA voxel raycast returning the first solid non-liquid block hit and the adjacent air face.
     */
    /** Shortest ray this raycaster will trace; matches the tool schema. */
    public static final int MIN_DISTANCE = 1;
    /** Longest ray this raycaster will trace; matches the tool schema. */
    public static final int MAX_DISTANCE = 128;

    /** Clamps a requested ray length to the range the schema advertises. */
    public static double clampDistance(double requested) {
        return Math.min(MAX_DISTANCE, Math.max(MIN_DISTANCE, requested));
    }

    public static JsonObject raycastSolidBlock(World world, double eyeX, double eyeY, double eyeZ, double dirX, double dirY, double dirZ, double maxDist) {
        if (world == null) return null;
        double limit = clampDistance(maxDist);
        int x = MathHelper.floor_double(eyeX);
        int y = MathHelper.floor_double(eyeY);
        int z = MathHelper.floor_double(eyeZ);

        int stepX = Double.compare(dirX, 0.0);
        int stepY = Double.compare(dirY, 0.0);
        int stepZ = Double.compare(dirZ, 0.0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dirX) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dirY) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dirZ) : Double.MAX_VALUE;

        double tMaxX = stepX > 0 ? (x + 1.0 - eyeX) * tDeltaX : (stepX < 0 ? (eyeX - x) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1.0 - eyeY) * tDeltaY : (stepY < 0 ? (eyeY - y) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1.0 - eyeZ) * tDeltaZ : (stepZ < 0 ? (eyeZ - z) * tDeltaZ : Double.MAX_VALUE);

        int prevX = x, prevY = y, prevZ = z;
        double dist = 0.0;
        int maxSteps = (int) Math.ceil(limit * 3.0) + 4;

        for (int step = 0; step < maxSteps && dist <= limit; step++) {
            if (!world.isRemote && !ChunkAccess.ensureBlockLoaded(world, x, z)) {
                // Treat the edge of loaded chunks as opaque. The chunk manager
                // may load it when the policy allows; otherwise the ray must
                // not silently generate world terrain.
                return null;
            }
            if (y > 0 && y < 255 && !world.isAirBlock(x, y, z)) {
                Block b = world.getBlock(x, y, z);
                if (b != null && !ItemClassifier.isLiquidBlock(b)) {
                    int meta = world.getBlockMetadata(x, y, z);
                    JsonObject hit = new JsonObject();
                    hit.addProperty("x", x);
                    hit.addProperty("y", y);
                    hit.addProperty("z", z);
                    hit.addProperty("name", ItemClassifier.getBlockDisplayName(b, meta));
                    String reg = Block.blockRegistry.getNameForObject(b);
                    hit.addProperty("registryName", reg != null ? reg : "");
                    hit.addProperty("id", Block.getIdFromBlock(b));
                    hit.addProperty("meta", meta);
                    hit.addProperty("distance", Math.round(dist * 10.0) / 10.0);
                    hit.addProperty("faceX", prevX);
                    hit.addProperty("faceY", prevY);
                    hit.addProperty("faceZ", prevZ);
                    return hit;
                }
            }
            prevX = x;
            prevY = y;
            prevZ = z;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                dist = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                dist = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                dist = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }
}
