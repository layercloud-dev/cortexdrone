package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.movement.impl.MovementContext;

/**
 * Base for movement actions. Navigation progress and completion are action
 * behavior, not service behavior.
 */
public abstract class MovementAction implements Action<MovementContext> {
    @Override
    public void tick(MovementContext context) {
        MovementSupport.tick(context);
    }
}
