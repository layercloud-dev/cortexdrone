package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import lombok.RequiredArgsConstructor;

/**
 * Executes the stop request: clears the path and holds position.
 */
@RequiredArgsConstructor
public final class StopAction implements Action<NavigationContext> {
    private final NavigationState state;

    @Override
    public void execute(NavigationContext context) {
        DroneController ctrl = context.controller();
        if (ctrl == null || ctrl.drone == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        if (this.state != null) {
            this.state.clearPath();
            this.state.stuckTicks = 0;
            this.state.hold(ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ);
        }
        FlightPathfinder.setDroneFlightTarget(ctrl.drone,
                ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, 0.06f);
        ctrl.drone.motionX *= 0.25;
        ctrl.drone.motionY *= 0.25;
        ctrl.drone.motionZ *= 0.25;
        ctrl.drone.statusText_$eq("Hovering\nStopped");
        context.result(NavigateResult.done());
        context.complete();
    }
}
