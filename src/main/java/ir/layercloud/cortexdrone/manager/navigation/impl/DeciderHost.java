package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;

/** The manager's wiring for {@link NavigationDecider}. */
final class DeciderHost implements NavigationDecider.Host {
    private final NavigationManager manager;

    DeciderHost(NavigationManager manager) {
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
    public boolean startDig(DroneController controller, NavigationSession session, int[] target) {
        return this.manager.startDigFor(controller, session, target);
    }
}
