package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementSupport;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import lombok.RequiredArgsConstructor;

/** Single-use command: dig straight down by depth or to an explicit point. */
@RequiredArgsConstructor
public final class DigDownAction extends MovementAction {
    private final AIDroneManager manager;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            int depth = Math.max(1, Math.min(64, request.getDepth()));
            target = MovementSupport.depthTarget(request.getController(), depth);
        }
        if (target == null) {
            context.result(MovementResult.failure("No active drone"));
            return;
        }
        MovementResult result = MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_DOWN, "DigDown");
        context.result(result);
        context.begin(result);
    }
}
