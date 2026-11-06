package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
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
     * - Uses controller's integrated velocity estimate (velX/Y/Z) for anti-overshoot braking.
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
        if (ctrl != null) {
            ctrl.velX = vx;
            ctrl.velY = vy;
            ctrl.velZ = vz;
        }
    }

    public static boolean isPlayerLookingAtDrone(EntityPlayerMP player, Drone drone) {
        if (player == null || drone == null || player.worldObj != drone.worldObj) return false;
        double ex = player.posX;
        double ey = player.posY + 1.62;
        double ez = player.posZ;
        double dx = drone.posX - ex;
        double dy = (drone.posY + 0.18) - ey;
        double dz = drone.posZ - ez;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.2 || dist > 4.5) return false;

        double yawRad = Math.toRadians(player.rotationYaw);
        double pitchRad = Math.toRadians(player.rotationPitch);
        double lx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double ly = -Math.sin(pitchRad);
        double lz = Math.cos(yawRad) * Math.cos(pitchRad);

        double dot = (dx * lx + dy * ly + dz * lz) / dist;
        return dot > 0.70;
    }

    public static double[] computeStableFollowAnchor(World world, EntityPlayerMP target, Drone d) {
        double px = target.posX;
        double py = target.posY + 1.65;
        double pz = target.posZ;

        double baseAngle = Math.atan2(d.posZ - pz, d.posX - px);
        double[] offsets = new double[]{0.0, 0.55, -0.55, 1.1, -1.1, 1.65, -1.65, 2.2, -2.2, Math.PI};
        double[] radii = new double[]{2.2, 1.8, 2.7};
        double[] yOffsets = new double[]{0.0, 0.45, 0.85, -0.25};

        for (double r : radii) {
            for (double yOff : yOffsets) {
                for (double aOff : offsets) {
                    double ang = baseAngle + aOff;
                    double cx = px + Math.cos(ang) * r;
                    double cy = py + yOff;
                    double cz = pz + Math.sin(ang) * r;
                    int bx = MathHelper.floor_double(cx);
                    int by = MathHelper.floor_double(cy);
                    int bz = MathHelper.floor_double(cz);
                    if (ItemClassifier.isPassableVoxel(world, bx, by, bz)
                            && FlightPathfinder.isFlightLineClear(world, px, py + 0.3, pz, cx, cy, cz)) {
                        return new double[]{cx, cy, cz};
                    }
                }
            }
        }
        return new double[]{px, target.posY + 1.8, pz};
    }

    private static boolean hasHeadroom(World world, int x, int y, int z) {
        return ItemClassifier.isPassableVoxel(world, x, y, z)
                && ItemClassifier.isPassableVoxel(world, x, y + 1, z);
    }

    public static double[] findMiningStandoff(World world, Drone d, int bx, int by, int bz) {
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

    public static int[] findBlockingTunnelBlock(World world, Drone d, int targetX, int targetY, int targetZ) {
        if (world == null || d == null) return null;
        int cx = MathHelper.floor_double(d.posX);
        int cy = MathHelper.floor_double(d.posY);
        int cz = MathHelper.floor_double(d.posZ);

        int maxSteps = 96;
        for (int step = 0; step < maxSteps; step++) {
            int remX = targetX - cx;
            int remY = targetY - cy;
            int remZ = targetZ - cz;
            if (Math.abs(remX) + Math.abs(remY) + Math.abs(remZ) <= 1) {
                break;
            }
            int[][] cands = new int[][]{
                    {remX > 0 ? 1 : (remX < 0 ? -1 : 0), 0, 0, Math.abs(remX)},
                    {0, remY > 0 ? 1 : (remY < 0 ? -1 : 0), 0, Math.abs(remY)},
                    {0, 0, remZ > 0 ? 1 : (remZ < 0 ? -1 : 0), Math.abs(remZ)}
            };
            for (int i = 0; i < 2; i++) {
                for (int j = i + 1; j < 3; j++) {
                    if (cands[j][3] > cands[i][3]) {
                        int[] tmp = cands[i];
                        cands[i] = cands[j];
                        cands[j] = tmp;
                    }
                }
            }
            boolean steppedAir = false;
            for (int i = 0; i < 3; i++) {
                if (cands[i][3] == 0) continue;
                int nx = cx + cands[i][0];
                int ny = cy + cands[i][1];
                int nz = cz + cands[i][2];
                if (ItemClassifier.isPassableVoxel(world, nx, ny, nz)) {
                    cx = nx;
                    cy = ny;
                    cz = nz;
                    steppedAir = true;
                    break;
                }
            }
            if (steppedAir) continue;

            for (int i = 0; i < 3; i++) {
                if (cands[i][3] == 0) continue;
                int nx = cx + cands[i][0];
                int ny = cy + cands[i][1];
                int nz = cz + cands[i][2];
                if (nx == targetX && ny == targetY && nz == targetZ) return null;
                if (ItemClassifier.isMineableBlock(world, nx, ny, nz)) {
                    return new int[]{nx, ny, nz};
                }
            }
            break;
        }

        for (int y = Math.min(253, targetY + 14); y > targetY; y--) {
            if (!ItemClassifier.isPassableVoxel(world, targetX, y, targetZ) && ItemClassifier.isMineableBlock(world, targetX, y, targetZ)) {
                if (findMiningStandoff(world, d, targetX, y, targetZ) != null) {
                    return new int[]{targetX, y, targetZ};
                }
            }
        }
        return null;
    }
}
