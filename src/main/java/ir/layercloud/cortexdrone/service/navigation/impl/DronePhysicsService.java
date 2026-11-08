package ir.layercloud.cortexdrone.service.navigation.impl;

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
    /**
     * Longest single collision sweep. The drone body is 0.75 wide, so a step
     * under half of that keeps wall and corner resolution accurate.
     */
    private static final double MAX_SUBSTEP = 0.35;
    /** Per-tick step ceiling; 3.75 / 0.35 rounds to 11. */
    private static final int MAX_SUBSTEPS = 16;

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

    /**
     * Allocation-free velocity integration with predictive braking & cruise-speed profile.
     * - Avoids Vec3 allocation (zero-pressure tick path).
     * - Distance-dependent max velocity: full cruise far away, ease-in near the target so the
     * drone arrives without oscillation and never walks into the floor (target is 3D, not ground-hugging).
     */
    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride) {
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
            // Water is a medium, not a boost. Raising the cap here made the
            // drone overshoot the surface and bob instead of entering it.
            if (inLiquid) cap = Math.min(cap, 0.34);
            // The braking distance must grow with the cap, otherwise a boosted
            // drone enters the ease-in zone far too fast and oscillates around
            // the target. 6 blocks is the tuned radius for the 0.75 far cap.
            double brakeRadius = 6.0 * Math.max(1.0, cap / 0.75);
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

            // Acceleration also scales, but sub-linearly: a linear 0.70 ramp at
            // 5x overshoots and oscillates. sqrt gives a ~12-tick spin-up and
            // keeps the proportional controller stable.
            double accelBase = maxVelOverride > 0.5 ? 0.14 : (double) Math.max(0.08f, d.targetAcceleration());
            if (cap > 0.75) accelBase = Math.max(accelBase, 0.14 * Math.sqrt(cap / 0.75));
            if (inLiquid) accelBase *= 1.1;
            double accel = Math.min(accelBase, distance) / distance;
            vx += dx * accel;
            vy += dy * accel;
            vz += dz * accel;
            vx = Math.max(-vMax, Math.min(vMax, vx));
            vy = Math.max(-vMax, Math.min(vMax, vy));
            vz = Math.max(-vMax, Math.min(vMax, vz));
        } else {
            vx = 0.0;
            vy = 0.0;
            vz = 0.0;
        }
        moveWithSubsteps(d, vx, vy, vz);
        double drag = inLiquid ? 0.86 : (double) d.drag();
        d.motionX = vx * drag;
        d.motionY = vy * drag;
        d.motionZ = vz * drag;
    }

    /**
     * Moves the drone in collision-safe sub-steps.
     *
     * <p>Vanilla {@code moveEntity} sweeps the AABB along one axis at a time,
     * which is correct for the ~0.4-block steps the drone was originally tuned
     * for. A 5x speed cap produces steps of up to 3.75 blocks in one tick; a
     * single sweep that long can resolve against a wall it should have stopped
     * at and then leave the drone embedded, or let it "step over" a one-block
     * gap. Splitting the step into chunks shorter than the drone's own half
     * width keeps the sweep resolution granular enough to behave like normal
     * flight.</p>
     */
    private static void moveWithSubsteps(Drone d, double vx, double vy, double vz) {
        double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (distance <= MAX_SUBSTEP) {
            d.moveEntity(vx, vy, vz);
            return;
        }
        int steps = Math.min(MAX_SUBSTEPS, (int) Math.ceil(distance / MAX_SUBSTEP));
        double sx = vx / steps;
        double sy = vy / steps;
        double sz = vz / steps;
        for (int i = 0; i < steps; i++) {
            d.moveEntity(sx, sy, sz);
        }
    }

    public static boolean hasHeadroom(World world, int x, int y, int z) {
        if (ItemClassifier.isDangerousLiquid(world.getBlock(x, y, z))
                || ItemClassifier.isDangerousLiquid(world.getBlock(x, y + 1, z))) {
            return false;
        }
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
                        int stepX = bx + Integer.compare(dx, 0);
                        int stepY = by + Integer.compare(dy, 0);
                        int stepZ = bz + Integer.compare(dz, 0);
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
