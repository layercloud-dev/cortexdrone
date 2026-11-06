package ir.layercloud.cortexdrone.action;

import ir.layercloud.cortexdrone.context.ActionContext;
import ir.layercloud.cortexdrone.service.action.ActionService;

/**
 * A command managed by an {@link ActionService}.
 *
 * <p>The service creates the action, executes it once, ticks it while its
 * context is retained, and releases it when it finishes. Actions contain the
 * operation logic; services only manage lifecycle and serialization.</p>
 *
 * @param <C> mutable state for this execution
 */
public interface Action<C extends ActionContext> {
    void execute(C context);

    /** Called once per server tick while the service retains this action. */
    default void tick(C context) {
    }

    /** Called when the service cancels this action. */
    default void cancel(C context) {
    }
}
