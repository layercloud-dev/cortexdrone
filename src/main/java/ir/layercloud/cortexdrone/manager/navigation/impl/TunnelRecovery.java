package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.DigPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Chooses the next automatic-tunnelling target for {@link NavigationManager}.
 *
 * <p>The escalation mirrors a player digging toward a goal, cheapest first:</p>
 *
 * <ol>
 *   <li>mine the first block on the straight fat ray to the goal;</li>
 *   <li>step up or down when the goal is far above/below and the floor or
 *       ceiling is the only obstruction;</li>
 *   <li>clear the nearest wedge in the 3x3x3 body shell, floor included;</li>
 *   <li>run a dig-aware A* through rock (Baritone's "cost of breaking" model)
 *       when the drone sits in a larger pocket or liquid pool.</li>
 * </ol>
 *
 * <p>Step 4 is what the live failures were missing: 50 of 52
 * "no tunnel direction found" snapshots had no mineable block inside the
 * 3x3x3 shell, only open pocket or water, while a wall existed farther along a
 * diagonal the straight ray never sampled.</p>
 */
final class TunnelRecovery {
    /** A dig-aware route never starts with a block farther than this. */
    private static final double MAX_FIRST_BREAK = 24.0D;

    private TunnelRecovery() {
    }

    /** Consecutive non-approaching digs before the tunnel is re-routed. */
    private static final int MAX_USELESS_DIGS = 24;

    /**
     * @return the next {@code {x,y,z}} to break, or {@code null} when the
     * planner cannot find any safe candidate
     */
    static int[] nextTarget(NavigationState state, NavigationSession session,
                            World world, Drone drone, EntityPlayerMP player) {
        if (world == null || drone == null) return null;

        int[] candidate = TunnelPlanner.nextObstruction(world, drone,
                state.goalX, state.goalY, state.goalZ, session.targetBlock);
        if (usable(candidate, session)) return candidate;

        candidate = TunnelPlanner.nextVertical(world, drone, player,
                state.goalX, state.goalY, state.goalZ);
        if (usable(candidate, session)) return candidate;

        candidate = TunnelPlanner.nextLocalObstruction(world, drone,
                state.goalX, state.goalZ);
        if (usable(candidate, session)) return candidate;

        candidate = DigPathfinder.firstBreak(world, drone,
                state.goalX, state.goalY, state.goalZ, session.rejectedDigs).breakBlock;
        if (!usable(candidate, session)) return null;
        if (drone.getDistance(candidate[0] + 0.5D, candidate[1] + 0.5D,
                candidate[2] + 0.5D) > MAX_FIRST_BREAK) {
            return null;
        }
        return candidate;
    }

    private static boolean usable(int[] target, NavigationSession session) {
        if (target == null) return false;
        if (session.targetBlock != null
                && target[0] == session.targetBlock[0]
                && target[1] == session.targetBlock[1]
                && target[2] == session.targetBlock[2]) {
            return false;
        }
        return !session.rejectedDigs.contains(
                FlightPathfinder.packBlockPos(target[0], target[1], target[2]));
    }

    /**
     * True when the open tunnel is circling and must be abandoned.
     *
     * <p>A tunnel that no longer approaches the goal is re-targeted: the live
     * log spent all 128 blocks zig-zagging at one spot, so keeping the same
     * heading only drains the budget.</p>
     */
    static boolean circling(DroneLogger logger, DroneController controller,
                            NavigationSession session) {
        if (session.uselessDigs < MAX_USELESS_DIGS) return false;
        logger.log("NAV", controller, "Tunnel circling",
                "uselessDigs=" + session.uselessDigs
                        + " dug=" + session.dug + "/" + session.maxDigBlocks
                        + " -> re-routing");
        session.uselessDigs = 0;
        session.digBestDistance = Double.MAX_VALUE;
        session.detours = 0;
        session.digTarget = null;
        return true;
    }

    /**
     * Tracks whether digging actually approaches the goal.
     *
     * <p>A long run of digs that never reduce the distance means the tunnel is
     * circling — the live log spent all 128 blocks zig-zagging at one spot.
     * The counter lets recovery re-target instead of draining the budget.</p>
     */
    static void trackDigProgress(NavigationSession session, Drone drone,
                                 NavigationState state) {
        if (drone == null || state == null) return;
        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (distance < session.digBestDistance - 0.75D) {
            session.digBestDistance = distance;
            session.uselessDigs = 0;
        } else {
            session.uselessDigs++;
        }
    }

    /**
     * A short flight step toward the goal when nothing is mineable.
     *
     * <p>This is the water/pocket escape. Two distinct live failures needed
     * it:</p>
     *
     * <ul>
     *   <li>a drone hovering <em>above</em> a pool could not descend, because
     *       the vertical planner only accepts a solid block it may break and
     *       water is neither solid nor diggable;</li>
     *   <li>a drone in a flooded pocket had no mineable voxel in its 3x3x3
     *       shell, so tunnelling reported "no tunnel direction found" while
     *       open water led straight to the goal.</li>
     * </ul>
     *
     * <p>The candidate must be closer to the goal than the drone, so this can
     * never turn into a random walk: it either makes progress or returns
     * {@code null} and the caller fails honestly.</p>
     *
     * @return an open standoff to fly to, or {@code null} when no step improves
     */
    static double[] escapeStep(World world, Drone drone, NavigationState state) {
        if (world == null || drone == null) return null;
        double dx = state.goalX - drone.posX;
        double dy = state.goalY - drone.posY;
        double dz = state.goalZ - drone.posZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0D) return null;
        double current = len;
        double[][] directions = new double[][]{
                {dx / len, dy / len, dz / len},
                {dx / len, 0.0D, dz / len},
                {0.0D, dy < 0 ? -1.0D : 1.0D, 0.0D},
                {0.0D, 0.0D, dz < 0 ? -1.0D : 1.0D},
                {dx < 0 ? -1.0D : 1.0D, 0.0D, 0.0D}
        };
        for (double[] direction : directions) {
            for (double step = 4.0D; step >= 1.5D; step -= 0.5D) {
                int tx = (int) Math.floor(drone.posX + direction[0] * step);
                int ty = (int) Math.floor(drone.posY + direction[1] * step);
                int tz = (int) Math.floor(drone.posZ + direction[2] * step);
                if (!openForDrone(world, tx, ty, tz)) continue;
                double remaining = Math.sqrt(
                        (state.goalX - (tx + 0.5D)) * (state.goalX - (tx + 0.5D))
                                + (state.goalY - (ty + 0.35D)) * (state.goalY - (ty + 0.35D))
                                + (state.goalZ - (tz + 0.5D)) * (state.goalZ - (tz + 0.5D)));
                if (remaining > current - 0.75D) continue;
                return new double[]{tx + 0.5D, ty + 0.35D, tz + 0.5D};
            }
        }
        return null;
    }

    private static boolean openForDrone(World world, int x, int y, int z) {
        if (!world.blockExists(x, y, z)) return false;
        if (ItemClassifier.isDangerousLiquid(world.getBlock(x, y, z))
                || ItemClassifier.isDangerousLiquid(world.getBlock(x, y + 1, z))) {
            return false;
        }
        return ItemClassifier.isPassableVoxel(world, x, y, z)
                && ItemClassifier.isPassableVoxel(world, x, y + 1, z);
    }
}
