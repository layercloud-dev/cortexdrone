package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: carve a tunnel to an explicit point. */
@RequiredArgsConstructor
final class TunnelAction implements Action<MovementContext> {
    private final AIDroneManager manager;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            context.result(MovementResult.failure(
                "tunnel requires x/y/z (or standoffOf:{x,y,z})"));
            return;
        }
        MovementResult result = MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_TUNNEL, "Tunnel");
        context.result(result);
        context.begin(result);
    }
}
