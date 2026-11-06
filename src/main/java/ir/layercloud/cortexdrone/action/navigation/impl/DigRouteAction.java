package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import lombok.RequiredArgsConstructor;

/**
 * Executes one navigation dig route. The action queues the route, then ticks
 * the block work itself until the queue and the current target are empty.
 */
@RequiredArgsConstructor
public final class DigRouteAction implements Action<NavigationContext> {
    private final BuildingManager buildingManager;

    @Override
    public void execute(NavigationContext context) {
        int queued = this.buildingManager.startDigRoute(
                context.controller(), context.digPath(), context.label());
        if (queued <= 0) {
            context.result(NavigateResult.failure("No dig route could be queued"));
            context.complete();
            return;
        }
        context.result(NavigateResult.moved(false));
        context.retain();
    }

    @Override
    public void tick(NavigationContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            context.complete();
            return;
        }
        this.buildingManager.tickMining(controller);
        if (!controller.isMining()) {
            context.result(NavigateResult.done());
            context.complete();
        }
    }
}
