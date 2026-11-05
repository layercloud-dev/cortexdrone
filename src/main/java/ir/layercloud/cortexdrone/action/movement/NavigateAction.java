package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;

/** General flight navigation. */
public class NavigateAction implements MovementAction {
    private final AIDroneManager manager;

    public NavigateAction(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public String operation() { return "navigate"; }

    @Override
    public MovementResult execute(MovementRequest request) {
        String route = MovementSupport.route(request.route(), MovementSupport.ROUTE_AUTO);
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            return MovementResult.failure("navigate requires x/y/z (or standoffOf:{x,y,z})");
        }
        return MovementSupport.start(this.manager, request, target, route, "Navigate");
    }
}
