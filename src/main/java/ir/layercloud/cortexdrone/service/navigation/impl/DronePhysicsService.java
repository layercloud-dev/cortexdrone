package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * 3D drone physics step integrator, follow-anchor solver, mining standoff calculator,
 * and tunnel obstruction locator.
 */
@UtilityClass
public final class DronePhysicsService {

    public static String describeNearestObstruction(World world, double x, double y, double z) {
        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!ItemClassifier.isPassableVoxel(world, bx + dx, by + dy, bz + dz)) {
                        Block b = world.getBlock(bx + dx, by + dy, bz + dz);
                        if (b != null) {
                            return b.getLocalizedName() + "@(" + (bx + dx) + "," + (by + dy) + "," + (bz + dz) + ")";
                        }
                    }
                }
            }
        }
        return "none";
    }

    public static void clearNearbyLeafObstruction(World world, DroneController ctrl) {
        if (world == null || ctrl == null || ctrl.drone == null) return;
        int bx = MathHelper.floor_double(ctrl.drone.posX);
        int by = MathHelper.floor_double(ctrl.drone.posY);
        int bz = MathHelper.floor_double(ctrl.drone.posZ);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (y <= 1 || y >= 254 || world.isAirBlock(x, y, z)) continue;
                    Block b = world.getBlock(x, y, z);
                    if (ItemClassifier.isLeafBlock(b)) {
                        world.setBlockToAir(x, y, z);
                    }
                }
            }
        }
    }

    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride) {
        runServerSidePhysicsStep(d, maxVelOverride, null);
    }

    /**
     * Allocation-free velocity integration with predictive braking & cruise-speed profile.
     * - Avoids Vec3 allocation (zero-pressure tick path).
     * - Distance-dependent max velocity: full cruise far away, ease-in near the target so the
     * drone arrives without oscillation and never walks into the floor (target is 3D, not ground-hugging).
     */
    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride, DroneController ctrl) {
        World w = d.worldObj;
        boolean inLiquid = d.isInWater() || d.handleLavaMovement() || (w != null && ItemClassifier.isLiquidBlock(w.getBlock(
                MathHelper.floor_double(d.posX),
                MathHelper.floor_double(d.posY),
                MathHelper.floor_double(d.posZ)
        )));
        double dx = (double) d.targetX() - d.posX;
        double dy = (double) d.targetY() - d.posY;
        double dz = (double) d.targetZ() - d.posZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double vx = d.motionX;
        double vy = d.motionY;
        double vz = d.motionZ;
        double speedSq = vx * vx + vy * vy + vz * vz;

        if (distance > 0.0 && (distance > 0.005 || speedSq > 0.000025)) {
            // Cruise profile: vMax(r) = clamp(cap * min(1, r / brakeRadius), minFloor, cap)
            double cap = maxVelOverride > 0.0 ? maxVelOverride : (double) d.maxVelocity();
            if (cap <= 0.0) cap = 1.15;
            if (inLiquid) cap = Math.max(cap, 0.55);
            double brakeRadius = 6.0;
            double cruiseFrac = Math.min(1.0, distance / brakeRadius);
            double vMax = Math.max(0.28, cap * cruiseFrac);
            // If already carrying too much velocity toward the target, brake early (predictive stopping)
            double closing = distance > 0.001 ? ((vx * dx + vy * dy + vz * dz) / distance) : 0.0;
            double stopDist = (closing * closing) / (2.0 * 0.35) + 0.4;
            if (stopDist > distance && closing > 0.0) {
                double brake = Math.min(closing, (distance + 0.4) * 0.45 + 0.05);
                double scale = closing > 0.001 ? (brake / closing) : 1.0;
                vx *= (0.55 + 0.45 * scale);
                vy *= (0.55 + 0.45 * scale);
                vz *= (0.55 + 0.45 * scale);
            }

            double accelBase = maxVelOverride > 0.5 ? 0.24 : (double) Math.max(0.10f, d.targetAcceleration());
            if (inLiquid) accelBase *= 1.6;
            double accel = Math.min(accelBase, distance) / distance;
            vx += dx * accel;
            vy += dy * accel + (inLiquid && dy >= -0.1 ? 0.03 : 0.0);
            vz += dz * accel;
            vx = Math.max(-vMax, Math.min(vMax, vx));
            vy = Math.max(-vMax, Math.min(vMax, vy));
            vz = Math.max(-vMax, Math.min(vMax, vz));
        } else {
            vx = 0.0;
            vy = 0.0;
            vz = 0.0;
        }
        d.moveEntity(vx, vy, vz);
        double drag = inLiquid ? 0.90 : (double) d.drag();
        d.motionX = vx * drag;
        d.motionY = vy * drag;
        d.motionZ = vz * drag;
    }

    public static boolean hasHeadroom(World world, int x, int y, int z) {
        return ItemClassifier.isPassableVoxel(world, x, y, z)
                && ItemClassifier.isPassableVoxel(world, x, y + 1, z);
    }

    public static double[] findBlockStandoff(World world, Drone d, int bx, int by, int bz) {
        if (world == null || d == null) return null;
        double tx = bx + 0.5;
        double ty = by + 0.5;
        double tz = bz + 0.5;
        double bestScore = Double.MAX_VALUE;
        double[] best = null;

        int[][] cardinals = new int[][]{
                {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
        };
        for (int[] c : cardinals) {
            int ax = bx + c[0];
            int ay = by + c[1];
            int az = bz + c[2];
            if (hasHeadroom(world, ax, ay, az)) {
                double cx = ax + 0.5;
                double cy = ay + 0.35;
                double cz = az + 0.5;
                double distFromDrone = d.getDistance(cx, cy, cz);
                if (distFromDrone < bestScore) {
                    bestScore = distFromDrone;
                    best = new double[]{cx, cy, cz};
                }
            }
        }
        if (best != null) return best;

        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int ax = bx + dx;
                        int ay = by + dy;
                        int az = bz + dz;
                        if (!hasHeadroom(world, ax, ay, az)) continue;
                        int stepX = bx + (dx > 0 ? 1 : (dx < 0 ? -1 : 0));
                        int stepY = by + (dy > 0 ? 1 : (dy < 0 ? -1 : 0));
                        int stepZ = bz + (dz > 0 ? 1 : (dz < 0 ? -1 : 0));
                        if (!ItemClassifier.isPassableVoxel(world, stepX, stepY, stepZ)) continue;

                        double cx = ax + 0.5;
                        double cy = ay + 0.35;
                        double cz = az + 0.5;
                        double distToBlock = Math.sqrt((cx - tx) * (cx - tx) + (cy - ty) * (cy - ty) + (cz - tz) * (cz - tz));
                        double distFromDrone = d.getDistance(cx, cy, cz);
                        double score = distFromDrone + distToBlock * 0.5;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new double[]{cx, cy, cz};
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
