package ir.layercloud.cortexdrone.action.movement.impl;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementPlan;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.action.movement.MovementTarget;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import lombok.RequiredArgsConstructor;

/** Single-use command: surface, then optionally continue to a final point. */
@RequiredArgsConstructor
public final class DigUpAction extends MovementAction {
    private final MovementService service;
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        DroneController controller = this.request.getController();
        MovementTarget requested = this.service.resolveTarget(this.request);
        MovementTarget target = requested != null
            ? requested
            : this.service.surfaceTarget(controller);
        if (target == null) {
            context.result(MovementResult.failure("No active drone"));
            return;
        }

        MovementTarget finalTarget = null;
        boolean viaSurface = false;
        if (requested != null && !this.service.inOpenSky(controller)) {
            MovementTarget surface = this.service.surfaceTarget(controller);
            if (surface != null) {
                target = surface;
                finalTarget = requested;
                viaSurface = true;
            }
        }

        MovementResult result = this.service.startNavigation(this.request,
            MovementPlan.builder()
                .target(target)
                .route(MovementRoute.UP)
                .finalTarget(finalTarget)
                .viaSurface(viaSurface)
                .build(),
            "DigUp");
        context.result(result);
        context.begin(result);
    }
}
