package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.NavigateAction;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;

/**
 * Starts and advances one flight leg.
 *
 * <p>Extracted from {@code NavigationManager}. A leg is "plan a route, retain the
 * action, tick it until it finishes or the watchdog fires". Keeping that in one
 * class means the manager only decides *which* leg to run, and the leg's own
 * lifecycle (start, tick, watchdog, result) is readable end to end.</p>
 */
final class NavigationLegRunner {
    /** A leg that moved at least this far counts as progress. */
    private static final double PROGRESS_MOVE_SQ = 0.0025D;

    /** Everything the runner needs from the manager. */
    interface Host {
        NavigationState state(DroneController controller);

        void updateTicket(DroneController controller, NavigationState state);

        void fail(DroneController controller, String reason);

        /** Handles a finished leg's result. */
        void handleResult(DroneController controller, NavigationSession session,
                          NavigationContext context, NavigateResult result);

        /** Handles an in-progress leg's result. */
        void handleProgress(DroneController controller, NavigationSession session,
                            NavigateResult result);

        /** Enters recovery after a stalled leg. */
        void recover(DroneController controller, NavigationSession session, String reason);

        /** Breaks a pending dig target. */
        void digPending(DroneController controller, NavigationSession session);
    }

    private final Host host;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbs;

    NavigationLegRunner(Host host, FlightPathfinder pathfinder, BreadcrumbService breadcrumbs) {
        this.host = host;
        this.pathfinder = pathfinder;
        this.breadcrumbs = breadcrumbs;
    }

    /** Plans and starts a leg toward the given point. */
    void start(DroneController controller, NavigationSession session,
               double targetX, double targetY, double targetZ, String label) {
        // A passenger is only flown in open sky; see RiderGuard for why a
        // two-high corridor that fits the drone still crushes the rider.
        if (RiderGuard.blocksTunnel(session)
                && !RiderGuard.routeAllowed(controller.drone, targetX, targetY, targetZ)) {
            this.host.fail(controller, RiderGuard.routeRefusal(targetX, targetY, targetZ));
            return;
        }
        session.phase = NavigationSession.Phase.FLIGHT;
        NavigationContext context = new NavigationContext(controller, null, NavigationMode.FLIGHT,
                targetX, targetY, targetZ, label);
        NavigateAction action = new NavigateAction(this.pathfinder, this.breadcrumbs,
                this.host.state(controller));
        action.execute(context);
        // The action has just planned a fresh path; claim its corridor before
        // vanilla can evict chunks that are not near a player.
        this.host.updateTicket(controller, this.host.state(controller));
        if (context.isActive()) {
            context.retain();
            session.leg = new NavigationSession.Leg(action, context);
            session.watchdog.begin(distance(controller.drone, targetX, targetY, targetZ),
                    this.host.state(controller).speedMultiplier);
            session.lastLegMoved = false;
        } else {
            session.leg = null;
            this.host.handleResult(controller, session, context, context.result());
        }
    }

    /** Advances the active leg by one tick. */
    void tick(DroneController controller, NavigationSession session) {
        NavigationSession.Leg leg = session.leg;
        if (!leg.context.isActive()) {
            session.leg = null;
            this.host.handleResult(controller, session, leg.context, leg.context.result());
            return;
        }
        leg.action.tick(leg.context);
        NavigationState state = this.host.state(controller);
        if (state.lastMoveSq >= PROGRESS_MOVE_SQ) session.lastLegMoved = true;
        if (!leg.context.isActive()) {
            session.leg = null;
            this.host.handleResult(controller, session, leg.context, leg.context.result());
            return;
        }
        Drone drone = controller.drone;
        double toTarget = leg.targetDistance(drone.posX, drone.posY, drone.posZ);
        if (session.watchdog.tick(toTarget, state.lastMoveSq)) {
            session.leg = null;
            leg.action.cancel(leg.context);
            session.failedRecoveries = session.lastLegMoved ? 0 : session.failedRecoveries + 1;
            this.host.recover(controller, session, "Flight leg stalled");
            return;
        }
        NavigateResult result = leg.context.result();
        if (result != null) this.host.handleProgress(controller, session, result);
    }

    /** Cancels the active leg, if any. */
    static void cancel(NavigationSession session) {
        if (session.leg != null && session.leg.context.isActive()) {
            session.leg.action.cancel(session.leg.context);
        }
        session.leg = null;
    }

    private static double distance(Drone drone, double x, double y, double z) {
        return drone.getDistance(x, y, z);
    }
}
