package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;

/** Carve a tunnel to an explicit destination. */
public class TunnelAction implements MovementAction {
    private final AIDroneManager manager;

    public TunnelAction(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public String operation() { return "tunnel"; }

    @Override
    public MovementResult execute(MovementRequest request) {
        MovementSupport.Target target = MovementSupport.resolveTarget(request);
        if (target == null) {
            return MovementResult.failure("tunnel requires x/y/z (or standoffOf:{x,y,z})");
        }
        return MovementSupport.start(this.manager, request, target,
            MovementSupport.ROUTE_TUNNEL, "Tunnel");
    }
}
