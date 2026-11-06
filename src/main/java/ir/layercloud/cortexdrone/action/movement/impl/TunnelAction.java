package ir.layercloud.cortexdrone.action.movement.impl;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementPlan;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.action.movement.MovementTarget;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import lombok.RequiredArgsConstructor;

/** Single-use command: carve a tunnel to an explicit point. */
@RequiredArgsConstructor
public final class TunnelAction extends MovementAction {
    private final MovementService service;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        MovementTarget target = this.service.resolveTarget(this.request);
        if (target == null) {
            context.result(MovementResult.failure(
                "tunnel requires x/y/z (or standoffOf:{x,y,z})"));
            return;
        }
        MovementResult result = this.service.startNavigation(this.request,
            MovementPlan.builder()
                .target(target)
                .route(MovementRoute.TUNNEL)
                .build(),
            "Tunnel");
        context.result(result);
        context.begin(result);
    }
}
