package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;

/** The manager's wiring for {@link NavigationLegRunner}. */
final class LegRunnerHost implements NavigationLegRunner.Host {
    private final NavigationManager manager;

    LegRunnerHost(NavigationManager manager) {
        this.manager = manager;
    }

    @Override
    public NavigationState state(DroneController controller) {
        return this.manager.state(controller);
    }

    @Override
    public void updateTicket(DroneController controller, NavigationState state) {
        this.manager.updateTicket(controller, state);
    }

    @Override
    public void fail(DroneController controller, String reason) {
        this.manager.failFor(controller, reason);
    }

    @Override
    public void handleResult(DroneController controller, NavigationSession session,
                             NavigationContext context, NavigateResult result) {
        this.manager.handleResultFor(controller, session, context, result);
    }

    @Override
    public void handleProgress(DroneController controller, NavigationSession session,
                               NavigateResult result) {
        this.manager.handleProgressFor(controller, session, result);
    }

    @Override
    public void recover(DroneController controller, NavigationSession session, String reason) {
        this.manager.recoverFor(controller, session, reason);
    }

    @Override
    public void digPending(DroneController controller, NavigationSession session) {
        this.manager.digPendingFor(controller, session);
    }
}
