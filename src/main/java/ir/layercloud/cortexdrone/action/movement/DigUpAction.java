package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;

/** Go up through the surface, optionally continuing to a final destination. */
public class DigUpAction implements MovementAction {
    private final AIDroneManager manager;

    public DigUpAction(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public String operation() { return "digup"; }

    @Override
    public MovementResult execute(MovementRequest request) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) target = MovementSupport.surfaceTarget(request.controller());
        if (target == null) return MovementResult.failure("No active drone");
        return MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_UP, "DigUp");
    }
}
