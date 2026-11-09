package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

/**
 * Chooses the next route for a session that is planning.
 *
 * <p>Extracted from {@code NavigationManager}. The decision order is the heart of
 * navigation and is worth reading on its own:</p>
 * <ol>
 *   <li><b>tunnel-to-block</b> — a buried standoff target keeps clearing the line;</li>
 *   <li><b>direct</b> — a clear fat ray needs no trail, no A* and no recovery;</li>
 *   <li><b>trail</b> — backtrack along recorded passable positions;</li>
 *   <li><b>flight</b> — hand the goal to the A* pathfinder.</li>
 * </ol>
 */
final class NavigationDecider {
    /** Everything the decider needs from the manager. */
    interface Host {
        NavigationState state(DroneController controller);

        void startLeg(DroneController controller, NavigationSession session,
                      double x, double y, double z, String label);

        /** Starts digging the obstruction, or returns false when it cannot. */
        boolean startDig(DroneController controller, NavigationSession session, int[] target);
    }

    private final Host host;
    private final BreadcrumbService breadcrumbs;
    private final DroneLogger logger;

    NavigationDecider(Host host, BreadcrumbService breadcrumbs, DroneLogger logger) {
        this.host = host;
        this.breadcrumbs = breadcrumbs;
        this.logger = logger;
    }

    /** Picks and starts the next leg. */
    void decide(DroneController controller, NavigationSession session) {
        if (session.replanCooldown > 0) return;

        NavigationState state = this.host.state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

        if (session.targetBlock != null) {
            decideTunnelToBlock(controller, session, state, world, drone);
            return;
        }
        if (FlightPathfinder.isFlightLineClear(world, drone.posX, drone.posY, drone.posZ,
                state.goalX, state.goalY, state.goalZ)) {
            session.usingTrail = false;
            session.reason = "direct";
            this.host.startLeg(controller, session, state.goalX, state.goalY, state.goalZ,
                    "Direct flight");
            return;
        }
        // Once tunnelling has started, keep tunnelling: a trail backtrack would
        // fly away from the hole being dug and reset the session's progress.
        if (!session.trailRejected && session.dug == 0 && tryTrail(controller, session, state)) {
            return;
        }
        session.usingTrail = false;
        session.reason = "flight";
        this.host.startLeg(controller, session, state.goalX, state.goalY, state.goalZ, "Flight");
    }

    /** A buried standoff target: clear the line instead of aiming at the block. */
    private void decideTunnelToBlock(DroneController controller, NavigationSession session,
                                     NavigationState state, World world, Drone drone) {
        int[] obstruction = TunnelPlanner.nextObstruction(world, drone,
                session.targetBlock[0] + DroneGeometry.CELL_CENTER, session.targetBlock[1] + DroneGeometry.CELL_CENTER,
                session.targetBlock[2] + DroneGeometry.CELL_CENTER, session.targetBlock);
        if (obstruction != null) {
            session.reason = "tunnel-to-block";
            if (this.host.startDig(controller, session, obstruction)) return;
        }
        session.reason = "approach";
        this.host.startLeg(controller, session, state.goalX, state.goalY, state.goalZ,
                "Approach block");
    }

    /** Flies to a useful trail endpoint; false when no useful trail exists. */
    private boolean tryTrail(DroneController controller, NavigationSession session,
                             NavigationState state) {
        TrailPlan trail = this.breadcrumbs.planBacktrack(controller,
                state.goalX, state.goalY, state.goalZ);
        if (trail == null || !trail.isUseful()) return false;
        Breadcrumb endpoint = trail.getEndpoint();
        session.usingTrail = true;
        session.reason = "trail-" + trail.getReason();
        this.host.startLeg(controller, session,
                endpoint.getX() + DroneGeometry.CELL_CENTER,
                endpoint.getY() + DroneGeometry.HOVER_OFFSET,
                endpoint.getZ() + DroneGeometry.CELL_CENTER,
                "Trail endpoint (" + trail.getReason() + ")");
        this.logger.log("NAV", controller, "Flying to trail endpoint (" + trail.getReason() + ")",
                "point=(" + endpoint.getX() + "," + endpoint.getY() + "," + endpoint.getZ() + ")");
        return true;
    }
}
