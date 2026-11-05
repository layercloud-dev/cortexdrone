package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;

/** Dig straight down by depth, or to an explicit destination. */
public class DigDownAction implements MovementAction {
    private final AIDroneManager manager;

    public DigDownAction(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public String operation() { return "digdown"; }

    @Override
    public MovementResult execute(MovementRequest request) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            int depth = Math.max(1, Math.min(64, request.depth()));
            target = MovementSupport.depthTarget(request.controller(), depth);
        }
        if (target == null) return MovementResult.failure("No active drone");
        return MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_DOWN, "DigDown");
    }
}
