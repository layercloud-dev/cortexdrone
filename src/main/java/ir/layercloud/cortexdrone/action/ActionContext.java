package ir.layercloud.cortexdrone.action;

import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Lifecycle state for one action invocation.
 *
 * <p>A context is live as soon as it is created. Short operations finish during
 * {@link Action#handle} and are discarded by the registry when the call
 * returns. Long-running operations call {@link #retain()} so the registry keeps
 * ticking them until {@link #complete()} or {@link #cancel()} is called.</p>
 */
public interface ActionContext {
    DroneController controller();

    EntityPlayerMP player();

    String operation();

    long startedTick();

    /** True until the operation completes or is cancelled. */
    boolean isActive();

    /** True when this context must survive the current RPC call. */
    boolean isRetained();

    /** Keeps this context alive across ticks after the RPC call returns. */
    void retain();

    /** Called once per server tick while this context is retained. */
    void tick();

    /** Mark the operation finished and release the context. */
    void complete();

    /** Cancel/discard without completing normally. */
    void cancel();
}
