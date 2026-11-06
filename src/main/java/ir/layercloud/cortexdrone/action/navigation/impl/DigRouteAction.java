package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import lombok.RequiredArgsConstructor;

/** Executes one dig route by handing the blocking blocks to the building manager. */
@RequiredArgsConstructor
public final class DigRouteAction implements Action<NavigationContext> {
    private final BuildingManager buildingManager;

    @Override
    public void execute(NavigationContext context) {
        int queued = this.buildingManager.startDigRoute(
            context.controller(), context.digPath(), context.label());
        context.result(queued > 0
            ? NavigateResult.moved(false)
            : NavigateResult.failure("No dig route could be queued"));
        context.complete();
    }
}
