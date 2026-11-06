package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementSupport;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import lombok.RequiredArgsConstructor;

/** Single-use command: fly to an explicit point. */
@RequiredArgsConstructor
public final class NavigateAction extends MovementAction {
    private final AIDroneManager manager;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        String route = MovementSupport.route(request.getRoute(), MovementSupport.ROUTE_AUTO);
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            context.result(MovementResult.failure(
                "navigate requires x/y/z (or standoffOf:{x,y,z})"));
            return;
        }
        MovementResult result = MovementSupport.start(this.manager, request, target, route, "Navigate");
        context.result(result);
        context.begin(result);
    }
}
