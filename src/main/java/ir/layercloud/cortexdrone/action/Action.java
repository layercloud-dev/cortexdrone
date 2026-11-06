package ir.layercloud.cortexdrone.action;

import ir.layercloud.cortexdrone.context.ActionContext;

/**
 * A command managed by an action manager.
 *
 * <p>The manager creates the action, executes it once, ticks it while its
 * context is retained, and releases it when it finishes. Actions contain the
 * operation logic; managers only manage lifecycle and serialization.</p>
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
