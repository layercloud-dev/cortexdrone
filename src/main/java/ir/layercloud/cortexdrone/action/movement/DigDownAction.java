package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: dig straight down by depth or to an explicit point. */
@RequiredArgsConstructor
final class DigDownAction implements Action<MovementContext> {
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
