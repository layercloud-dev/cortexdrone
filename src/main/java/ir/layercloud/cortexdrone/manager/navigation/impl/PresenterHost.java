package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;

/**
 * The manager's wiring for {@link NavigationPresenter}.
 */
final class PresenterHost implements NavigationPresenter.Host {
    private final NavigationManager manager;

    PresenterHost(NavigationManager manager) {
        this.manager = manager;
    }

    @Override
    public NavigationState state(DroneController controller) {
        return this.manager.state(controller);
    }

    @Override
    public void releaseSession(DroneController controller) {
        this.manager.releaseSession(controller);
    }

    @Override
    public NavigationSession session(DroneController controller) {
        return this.manager.session(controller);
    }

    @Override
    public void cancelLeg(NavigationSession session) {
        this.manager.cancelLegFor(session);
    }

    @Override
    public void updateTicket(DroneController controller, NavigationState state) {
        this.manager.updateTicket(controller, state);
    }

    @Override
    public void reportFailure(DroneController controller, NavigationState state,
                              NavigationSession session, String reason) {
        this.manager.reportFailure(controller, state, session, reason);
    }
}
