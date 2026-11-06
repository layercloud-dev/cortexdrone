package ir.layercloud.cortexdrone.action.movement;

import lombok.Builder;
import lombok.Value;

/**
 * One action's navigation plan. The service applies this common plan to the
 * controller; the action decides the target, route and optional surface stop.
 */
@Value
@Builder
public class MovementPlan {
    MovementTarget target;
    MovementRoute route;
    MovementTarget finalTarget;
    boolean viaSurface;
}
