package ir.layercloud.cortexdrone.context;

/**
 * Mutable state for one action execution.
 *
 * <p>The service creates the context, the action mutates it, and the service
 * either discards it after the call or retains it alongside the action for
 * ticking. Once the action completes or is cancelled both are released.</p>
 */
public interface ActionContext {
    boolean isActive();

    boolean isRetained();

    /**
     * Finish the action normally and release the context.
     */
    void complete();

    /**
     * Cancel the action and release the context.
     */
    void cancel();
}
