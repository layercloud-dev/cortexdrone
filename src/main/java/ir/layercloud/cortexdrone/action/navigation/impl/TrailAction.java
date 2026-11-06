package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.navigation.impl.NavigationContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

/** Executes one breadcrumb-trail leg. */
public final class TrailAction implements Action<NavigationContext> {
    @Override
    public void execute(NavigationContext context) {
        tick(context);
        if (context.isActive()) context.retain();
    }

    @Override
    public void tick(NavigationContext context) {
        DroneController ctrl = context.controller();
        if (ctrl == null || ctrl.drone == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        if (context.trailFinished()) {
            context.result(NavigateResult.done());
            context.complete();
            return;
        }
        Breadcrumb point = context.currentTrailPoint();
        double x = point.getX() + 0.5;
        double y = point.getY() + 0.65;
        double z = point.getZ() + 0.5;
        if (ctrl.drone.getDistance(x, y, z) <= 0.75) {
            context.advanceTrail();
            context.countTick();
            if (context.trailFinished()) {
                context.result(NavigateResult.done());
                context.complete();
            } else {
                context.result(NavigateResult.moved(false));
            }
            return;
        }
        if (ctrl.stuckTicks >= 12) {
            context.result(NavigateResult.needsDig(true, false));
            context.complete();
            return;
        }
        ir.layercloud.cortexdrone.navigation.service.FlightPathfinder.setDroneFlightTarget(
            ctrl.drone, x, y, z, 0.1f);
        ctrl.drone.lightColor_$eq(0xFFCC00);
        ctrl.drone.statusText_$eq("Trail\n"
            + Math.max(0, context.trail().size() - context.trailIndex()) + " pts");
        context.countTick();
        context.result(NavigateResult.moved(false));
    }
}
