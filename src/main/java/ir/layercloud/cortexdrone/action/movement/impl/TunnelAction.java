package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementSupport;
import ir.layercloud.cortexdrone.context.action.movement.impl.MovementContext;
import lombok.RequiredArgsConstructor;

/** Single-use command: carve a tunnel to an explicit point. */
@RequiredArgsConstructor
public final class TunnelAction extends MovementAction {
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
