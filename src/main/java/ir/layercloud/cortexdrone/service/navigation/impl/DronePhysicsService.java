package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
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
    /** Per-tick motion sub-step ceiling, shared with the braking model. */
    static final double MAX_SUBSTEP = 0.35;
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
        double vx = d.motionX;
        double vy = d.motionY;
        double vz = d.motionZ;
        double distanceBefore = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        double speedSq = vx * vx + vy * vy + vz * vz;

        if (distanceBefore > 0.0 && (distanceBefore > 0.005 || speedSq > 0.000025)) {
            // All the cruise/brake/accelerate maths lives in FlightSpeedModel so
            // it can be simulated in tests; this method only reads the entity,
            // runs one model tick and writes the result back.
            FlightSpeedModel.State state = new FlightSpeedModel.State(d.posX, d.posY, d.posZ);
            state.motionX = vx;
            state.motionY = vy;
            state.motionZ = vz;
            float accelFloor = d.targetAcceleration();
            double drag = inLiquid ? 0.86 : (double) d.drag();
            FlightSpeedModel.step(state, d.targetX(), d.targetY(), d.targetZ(),
                    maxVelOverride, inLiquid, drag, accelFloor);
            double stepX = state.x - d.posX;
            double stepY = state.y - d.posY;
            double stepZ = state.z - d.posZ;
            moveWithSubsteps(d, stepX, stepY, stepZ);
            d.motionX = state.motionX;
            d.motionY = state.motionY;
            d.motionZ = state.motionZ;
            return;
        }
        // At the target: stop instead of coasting.
        d.moveEntity(0.0, 0.0, 0.0);
        d.motionX = 0.0;
        d.motionY = 0.0;
        d.motionZ = 0.0;
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
        double tx = bx + DroneGeometry.CELL_CENTER;
        double ty = by + DroneGeometry.CELL_CENTER;
        double tz = bz + DroneGeometry.CELL_CENTER;
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
                double cx = ax + DroneGeometry.CELL_CENTER;
                double cy = ay + DroneGeometry.AIM_OFFSET;
                double cz = az + DroneGeometry.CELL_CENTER;
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

                        double cx = ax + DroneGeometry.CELL_CENTER;
                        double cy = ay + DroneGeometry.AIM_OFFSET;
                        double cz = az + DroneGeometry.CELL_CENTER;
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
