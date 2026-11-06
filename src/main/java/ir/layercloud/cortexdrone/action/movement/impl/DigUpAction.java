package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementSupport;
import lombok.RequiredArgsConstructor;

/** Single-use command: surface, then optionally continue to a final point. */
@RequiredArgsConstructor
final class DigUpAction extends MovementAction {
    private final AIDroneManager manager;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) target = MovementSupport.surfaceTarget(request.getController());
        if (target == null) {
            context.result(MovementResult.failure("No active drone"));
            return;
        }
        MovementResult result = MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_UP, "DigUp");
        context.result(result);
        context.begin(result);
    }
}
