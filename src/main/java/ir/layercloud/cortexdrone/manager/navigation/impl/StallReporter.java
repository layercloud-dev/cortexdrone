package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.ClearLeafObstructionAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * Stall diagnostics for one navigation tick.
 *
 * <p>Logging is part of recovery, not decoration: the STUCK line tells the
 * operator whether the drone is wedged on a leaf, a wall or a bad waypoint.
 * The throttle keeps a looping drone from filling the log while the watchdog
 * and recovery work in the background.</p>
 */
@RequiredArgsConstructor
final class StallReporter {
    private final DroneLogger logger;

    void track(DroneController controller, NavigationState state, Drone drone,
               World world, double movedSq) {
        double distToTarget = drone.getDistance(drone.targetX(), drone.targetY(), drone.targetZ());
        if (distToTarget <= 0.70 || movedSq >= 0.0002) {
            state.stuckTicks = 0;
            return;
        }
        state.stuckTicks++;
        if (state.stuckTicks != 18 && state.stuckTicks % 30 != 0) return;
        state.lastStuckReason = FlightPathfinder.describeNearestObstruction(
                world, drone.posX, drone.posY, drone.posZ);
        String stuckKey = controller.id + "|" + state.lastStuckReason
                + "|" + (drone.posX * 4) + "," + (drone.posY * 4) + "," + (drone.posZ * 4)
                + "->" + (int) ((double) drone.targetX() * 4) + ","
                + (int) ((double) drone.targetY() * 4) + ","
                + (int) ((double) drone.targetZ() * 4);
        if (!stuckKey.equals(state.lastStuckKey)
                || state.ticks - state.lastStuckLogTick >= 240) {
            int bx = (int) Math.floor(drone.posX);
            int by = (int) Math.floor(drone.posY);
            int bz = (int) Math.floor(drone.posZ);
            StringBuilder detail = new StringBuilder(384);
            detail.append("obstruction=").append(state.lastStuckReason).append(" nearby=");
            appendNearby(detail, world, bx, by, bz);
            detail.append(" guess=").append(guess(world, bx, by, bz));
            this.logger.log("STUCK", controller,
                    String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            drone.posX, drone.posY, drone.posZ,
                            (double) drone.targetX(), (double) drone.targetY(),
                            (double) drone.targetZ()),
                    detail.toString());
            state.lastStuckKey = stuckKey;
            state.lastStuckLogTick = state.ticks;
        }
        // Leaves and other replaceable plants are cheap to clear and cause the
        // majority of cosmetic stalls; clearing them is not terrain damage in
        // the sense the recovery budget protects.
        NavigationContext context = new NavigationContext(controller, null,
                NavigationMode.FLIGHT, drone.posX, drone.posY, drone.posZ, "Leaf clearance");
        new ClearLeafObstructionAction().execute(context);
    }

    /**
     * Emits a one-line failure snapshot for post-mortem debugging.
     *
     * <p>Includes the drone's exact position, the requested goal, the failed
     * waypoint, recovery counters, every non-air block in the 3x3x3 body shell
     * and a guessed cause. This is the record that tells us why a goto failed
     * without having to reproduce the cave by hand.</p>
     */
    void failure(DroneController controller, NavigationState state,
                 NavigationSession session, String reason) {
        if (controller == null || controller.drone == null) {
            this.logger.log("NAV", controller, "Navigation failed", reason);
            return;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null) {
            this.logger.log("NAV", controller, "Navigation failed", reason);
            return;
        }
        int bx = (int) Math.floor(drone.posX);
        int by = (int) Math.floor(drone.posY);
        int bz = (int) Math.floor(drone.posZ);

        StringBuilder detail = new StringBuilder(512);
        detail.append("reason=").append(reason)
                .append(" pos=(").append(fmt(drone.posX)).append(',').append(fmt(drone.posY))
                .append(',').append(fmt(drone.posZ)).append(')')
                .append(" block=(").append(bx).append(',').append(by).append(',').append(bz).append(')')
                .append(" goal=(").append(fmt(state.goalX)).append(',').append(fmt(state.goalY))
                .append(',').append(fmt(state.goalZ)).append(')')
                .append(" wp=(").append(fmt(drone.targetX())).append(',').append(fmt(drone.targetY()))
                .append(',').append(fmt(drone.targetZ())).append(')')
                .append(" path=").append(state.pathIndex).append('/').append(state.pathWaypoints.size())
                .append(" partial=").append(state.lastPathPartial)
                .append(" expanded=").append(state.lastPathNodesExpanded);
        if (session != null) {
            detail.append(" dug=").append(session.dug).append('/').append(session.maxDigBlocks)
                    .append(" recov=").append(session.recoveries)
                    .append(" detours=").append(session.detours)
                    .append(" allowDig=").append(session.allowDig);
            if (session.targetBlock != null) {
                detail.append(" targetBlock=(").append(session.targetBlock[0]).append(',')
                        .append(session.targetBlock[1]).append(',').append(session.targetBlock[2])
                        .append(')');
            }
        }
        detail.append(" stuck=").append(state.lastStuckReason);
        detail.append(" nearby=");
        appendNearby(detail, world, bx, by, bz);
        detail.append(" guess=").append(guess(world, bx, by, bz));

        this.logger.log("NAV", controller, "Navigation failure snapshot", detail.toString());
    }

    private static void appendNearby(StringBuilder out, World world, int bx, int by, int bz) {
        boolean first = true;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    if (!world.blockExists(x, y, z)) continue;
                    Block b = world.getBlock(x, y, z);
                    if (b == null || b == Blocks.air) continue;
                    if (!first) out.append("; ");
                    first = false;
                    String role = dx == 0 && dz == 0
                            ? (dy == 0 ? "body" : (dy == 1 ? "head" : "above")) : "side";
                    out.append(role).append("@(").append(x).append(',').append(y).append(',')
                            .append(z).append(")=").append(ItemClassifier.getBlockDisplayName(b,
                                    world.getBlockMetadata(x, y, z)))
                            .append("[pass=").append(ItemClassifier.isPassableVoxel(world, x, y, z))
                            .append(",liquid=").append(ItemClassifier.isLiquidBlock(b))
                            .append(",mineable=").append(ItemClassifier.isMineableBlock(world, x, y, z))
                            .append(",hard=").append(fmt(b.getBlockHardness(world, x, y, z)))
                            .append(']');
                }
            }
        }
        if (first) out.append("none");
    }

    private static String guess(World world, int bx, int by, int bz) {
        if (!ItemClassifier.isPassableVoxel(world, bx, by, bz)) {
            return "body embedded in " + ItemClassifier.getBlockDisplayName(
                    world.getBlock(bx, by, bz), world.getBlockMetadata(bx, by, bz));
        }
        if (!ItemClassifier.isPassableVoxel(world, bx, by + 1, bz)) {
            return "head/ceiling embedded in " + ItemClassifier.getBlockDisplayName(
                    world.getBlock(bx, by + 1, bz), world.getBlockMetadata(bx, by + 1, bz));
        }
        int lava = 0;
        int protectedBlocks = 0;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    Block b = world.getBlock(x, y, z);
                    if (b == null || b == Blocks.air) continue;
                    if (ItemClassifier.isDangerousLiquid(b)) lava++;
                    else if (!ItemClassifier.isPassableVoxel(world, x, y, z)
                            && !ItemClassifier.isMineableBlock(world, x, y, z)
                            && !ItemClassifier.isLiquidBlock(b)) protectedBlocks++;
                }
            }
        }
        if (lava > 0) return "lava nearby (" + lava + " voxels), no safe standoff";
        if (protectedBlocks > 0) {
            return protectedBlocks + " protected/unbreakable block(s) block the line";
        }
        return "no mineable obstruction or reachable standoff (goal isolated or chunk unloaded)";
    }

    private static String fmt(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    private static String fmt(float value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
