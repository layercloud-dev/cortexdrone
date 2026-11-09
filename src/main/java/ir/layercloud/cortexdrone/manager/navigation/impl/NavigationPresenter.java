package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import li.cil.oc.common.entity.Drone;

/**
 * Terminal presentation for a navigation request.
 *
 * <p>Extracted from {@code NavigationManager}. Arriving and failing both update
 * the request outcome, the chunk ticket, the drone's own LED/status text and the
 * stall snapshot. Keeping them together means the two endings cannot drift, and
 * the manager stops mixing routing with presentation.</p>
 */
final class NavigationPresenter {
    /** LED colour shown on arrival. */
    static final int ARRIVED_LED = 0x00FFAA;

    /** Everything presentation needs from the manager. */
    interface Host {
        NavigationState state(DroneController controller);

        void releaseSession(DroneController controller);

        NavigationSession session(DroneController controller);

        void cancelLeg(NavigationSession session);

        void updateTicket(DroneController controller, NavigationState state);

        void reportFailure(DroneController controller, NavigationState state,
                           NavigationSession session, String reason);
    }

    private final Host host;

    NavigationPresenter(Host host) {
        this.host = host;
    }

    /** Marks the request arrived and puts the drone in its arrived state. */
    void arrive(DroneController controller, NavigationSession session) {
        NavigationState state = this.host.state(controller);
        this.host.releaseSession(controller);
        session.watchdog.begin(0.0);
        state.finishRequest(session.requestId, "arrived", "");
        // The drone is still an entity in this chunk; keep it force-loaded
        // until despawn or the normal policy releases it.
        this.host.updateTicket(controller, state);
        state.stuckTicks = 0;
        state.lastPathPartial = false;
        state.clearPath();
        if (session.targetBlock != null) {
            state.goalX = session.targetBlock[0] + DroneGeometry.CELL_CENTER;
            state.goalY = session.targetBlock[1] + DroneGeometry.CELL_CENTER;
            state.goalZ = session.targetBlock[2] + DroneGeometry.CELL_CENTER;
        }
        Drone drone = controller.drone;
        if (drone != null) {
            drone.lightColor_$eq(ARRIVED_LED);
            drone.statusText_$eq("Arrived\n"
                    + (int) Math.floor(state.goalX) + "," + (int) Math.floor(state.goalZ));
        }
    }

    /** Marks the request failed, clears the route and reports a snapshot. */
    void fail(DroneController controller, String reason) {
        if (controller == null) return;
        NavigationSession session = this.host.session(controller);
        this.host.releaseSession(controller);
        NavigationState state = this.host.state(controller);
        if (session != null) {
            this.host.cancelLeg(session);
            state.finishRequest(session.requestId, "failed", reason);
        }
        this.host.updateTicket(controller, state);
        state.error = reason;
        state.clearPath();
        state.stuckTicks = 0;
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.host.reportFailure(controller, state, session, reason);
    }
}
