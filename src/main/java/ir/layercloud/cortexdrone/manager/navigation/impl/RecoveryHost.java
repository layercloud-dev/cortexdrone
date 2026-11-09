package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;

/** The manager's wiring for {@link NavigationRecovery}. */
final class RecoveryHost implements NavigationRecovery.Host {
    private final NavigationManager manager;

    RecoveryHost(NavigationManager manager) {
        this.manager = manager;
    }

    @Override
    public NavigationState state(DroneController controller) {
        return this.manager.state(controller);
    }

    @Override
    public void startLeg(DroneController controller, NavigationSession session,
                         double x, double y, double z, String label) {
        this.manager.startLegFor(controller, session, x, y, z, label);
    }

    @Override
    public void fail(DroneController controller, String reason) {
        this.manager.failFor(controller, reason);
    }

    @Override
    public void digPending(DroneController controller, NavigationSession session) {
        this.manager.digPendingFor(controller, session);
    }
}
