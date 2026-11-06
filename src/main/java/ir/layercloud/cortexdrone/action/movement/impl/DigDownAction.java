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

/** Single-use command: dig straight down by depth or to an explicit point. */
@RequiredArgsConstructor
public final class DigDownAction extends MovementAction {
    private final MovementService service;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        MovementTarget target = this.service.resolveTarget(this.request);
        if (target == null) {
            int depth = Math.max(1, Math.min(64, this.request.getDepth()));
            target = this.service.depthTarget(this.request.getController(), depth);
        }
        if (target == null) {
            context.result(MovementResult.failure("No active drone"));
            return;
        }
        MovementResult result = this.service.startNavigation(this.request,
            MovementPlan.builder()
                .target(target)
                .route(MovementRoute.DOWN)
                .build(),
            "DigDown");
        context.result(result);
        context.begin(result);
    }
}
