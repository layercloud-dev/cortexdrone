package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.NavigationTuning;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

/**
 * One tick of an active navigation session.
 *
 * <p>Extracted from {@code NavigationManager.tick}. The order matters and is
 * documented here: record displacement, refresh the chunk corridor, enforce the
 * time budget, check arrival, advance the leg, then plan if idle.</p>
 */
final class NavigationTicker {
    /**
     * Hard cap on a single request's lifetime, in ticks.
     */
    static final int MAX_FLIGHT_TICKS = 12000;
    /**
     * Distance gain that counts as real progress, in blocks.
     */
    private static final double PROGRESS_EPSILON = 0.5D;

    /**
     * Everything the ticker needs from the manager.
     */
    interface Host {
        NavigationState state(DroneController controller);

        NavigationSession session(DroneController controller);

        void trackStall(DroneController controller, NavigationState state,
                        Drone drone, World world, double movedSq);

        void updateTicket(DroneController controller, NavigationState state);

        void arrive(DroneController controller, NavigationSession session);

        void fail(DroneController controller, String reason);

        void tickLeg(DroneController controller, NavigationSession session);

        void decide(DroneController controller, NavigationSession session);
    }

    private final Host host;

    NavigationTicker(Host host) {
        this.host = host;
    }

    /**
     * Advances one session, if the drone has one.
     */
    void tick(DroneController controller) {
        if (controller == null || controller.drone == null) return;
        NavigationState state = this.host.state(controller);
        state.ticks++;
        if (state.repathCooldown > 0) state.repathCooldown--;

        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null || world.isRemote) return;

        recordMovement(state, drone);
        NavigationSession session = this.host.session(controller);
        if (session == null) return;
        // A stale flight target must not produce misleading STUCK lines.
        this.host.trackStall(controller, state, drone, world, state.lastMoveSq);
        // Keep the rolling corridor claimed before the pathfinder reads it.
        this.host.updateTicket(controller, state);

        session.ticks++;
        if (session.ticks > MAX_FLIGHT_TICKS) {
            this.host.fail(controller, "Navigation timed out");
            return;
        }
        if (hasArrived(controller, session, state, drone)) return;
        if (!noteSessionProgress(controller, session, state, drone)) return;
        if (session.leg != null) {
            this.host.tickLeg(controller, session);
            return;
        }
        if (session.replanCooldown > 0) session.replanCooldown--;
        noteProgress(session, drone, state);
        if (session.leg == null) session.phase = NavigationSession.Phase.PLAN;
        if (session.phase == NavigationSession.Phase.PLAN) {
            this.host.decide(controller, session);
        }
    }

    private static void recordMovement(NavigationState state, Drone drone) {
        double movedSq = (drone.posX - state.lastX) * (drone.posX - state.lastX)
                + (drone.posY - state.lastY) * (drone.posY - state.lastY)
                + (drone.posZ - state.lastZ) * (drone.posZ - state.lastZ);
        state.lastX = drone.posX;
        state.lastY = drone.posY;
        state.lastZ = drone.posZ;
        state.lastMoveSq = movedSq;
    }

    /**
     * True when the session is finished and the drone has been told.
     */
    private boolean hasArrived(DroneController controller, NavigationSession session,
                               NavigationState state, Drone drone) {
        if (session.targetBlock != null && TunnelPlanner.inReach(drone,
                session.targetBlock[0], session.targetBlock[1], session.targetBlock[2])) {
            this.host.arrive(controller, session);
            return true;
        }
        // The arrival tolerance grows with speed: a 5x drone covers 0.85 blocks
        // in a fraction of a tick, so the tuned radius would never be met.
        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (session.targetBlock == null
                && distance <= DroneSpeed.arrivalRadius(state.speedMultiplier)) {
            this.host.arrive(controller, session);
            return true;
        }
        return false;
    }

    /**
     * Fails a request that has not moved closer to its goal for a full minute
     * and a half.
     *
     * <p>Per-leg watchdogs cannot see this: each detour is a fresh, short leg
     * and each one is individually "reasonable". The clay task in the live log
     * bounced between canopy detours for thirteen minutes. Ending it with a
     * normal navigation failure returns control to the model, which can pick a
     * different route instead of the harness spending the whole deadline.</p>
     */
    private boolean noteSessionProgress(DroneController controller, NavigationSession session,
                                        NavigationState state, Drone drone) {
        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (session.progressBestDistance == Double.MAX_VALUE) {
            session.progressBestDistance = distance;
        }
        if (distance < session.progressBestDistance - NavigationTuning.NO_PROGRESS_EPSILON) {
            session.progressBestDistance = distance;
            session.noProgressTicks = 0;
            return true;
        }
        session.noProgressTicks++;
        if (session.noProgressTicks > NavigationTuning.NO_PROGRESS_LIMIT_TICKS
                && distance > 3.0D) {
            this.host.fail(controller, "Navigation timed out (no progress toward goal)");
            return false;
        }
        return true;
    }

    /**
     * Resets the recovery budget whenever the drone actually gets closer.
     */
    private static void noteProgress(NavigationSession session, Drone drone, NavigationState state) {
        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (distance < session.bestDistance - PROGRESS_EPSILON) {
            session.bestDistance = distance;
            session.recoveries = 0;
            session.failedRecoveries = 0;
        }
    }
}
