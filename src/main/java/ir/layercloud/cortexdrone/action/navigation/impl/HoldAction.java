package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.navigation.impl.NavigationContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;

/** Executes a position hold at the drone's current location. */
public final class HoldAction implements Action<NavigationContext> {
    @Override
    public void execute(NavigationContext context) {
        DroneController ctrl = context.controller();
        if (ctrl == null || ctrl.drone == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        FlightPathfinder.setDroneFlightTarget(ctrl.drone,
            ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, 0.06f);
        ctrl.drone.motionX *= 0.25;
        ctrl.drone.motionY *= 0.25;
        ctrl.drone.motionZ *= 0.25;
        context.result(NavigateResult.done());
        context.complete();
    }
}
