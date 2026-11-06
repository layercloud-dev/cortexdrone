package ir.layercloud.cortexdrone.action;

/**
 * A single-use command.
 *
 * <p>An action is created by an {@link ActionService} from one request,
 * executed exactly once against its context, and then discarded. Actions must
 * not own long-lived controller or world state; the service does that.</p>
 *
 * @param <C> mutable state for this one execution
 */
@FunctionalInterface
public interface Action<C extends ActionContext> {
    void execute(C context);
}
