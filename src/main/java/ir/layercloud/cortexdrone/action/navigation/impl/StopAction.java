package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.navigation.impl.NavigationContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import lombok.RequiredArgsConstructor;

/** Executes the stop request: clears the path and holds position. */
@RequiredArgsConstructor
public final class StopAction implements Action<NavigationContext> {
    private final AIDroneManager manager;

    @Override
    public void execute(NavigationContext context) {
        DroneController ctrl = context.controller();
        if (ctrl == null || ctrl.drone == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        ctrl.pathWaypoints.clear();
        ctrl.pathIndex = 0;
        ctrl.pathGoalX = Double.NaN;
        ctrl.pathGoalY = Double.NaN;
        ctrl.pathGoalZ = Double.NaN;
        ctrl.repathCooldown = 0;
        ctrl.stuckTicks = 0;
        ctrl.goalX = ctrl.drone.posX;
        ctrl.goalY = ctrl.drone.posY;
        ctrl.goalZ = ctrl.drone.posZ;
        FlightPathfinder.setDroneFlightTarget(ctrl.drone,
            ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, 0.06f);
        ctrl.drone.motionX *= 0.25;
        ctrl.drone.motionY *= 0.25;
        ctrl.drone.motionZ *= 0.25;
        ctrl.drone.statusText_$eq("Hovering\nStopped");
        this.manager.setDroneMode(ctrl, ir.layercloud.cortexdrone.model.Mode.STAY,
            context.label() != null ? context.label() : "Navigation stop");
        context.result(NavigateResult.done());
        context.complete();
    }
}
