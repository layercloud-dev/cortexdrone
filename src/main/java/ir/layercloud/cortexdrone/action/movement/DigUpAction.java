package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: surface, then optionally continue to a final point. */
@RequiredArgsConstructor
final class DigUpAction implements Action<MovementContext> {
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
