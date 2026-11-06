package ir.layercloud.cortexdrone.context;

import ir.layercloud.cortexdrone.drone.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one action execution.
 *
 * <p>The service creates the context, the action mutates it, and the service
 * either discards it after the call or retains it alongside the action for
 * ticking. Once the action completes or is cancelled both are released.</p>
 */
public interface ActionContext {
    DroneController controller();

    EntityPlayerMP player();

    String operation();

    long startedTick();

    boolean isActive();

    boolean isRetained();

    /**
     * Keeps the action alive across ticks after the call returns.
     */
    void retain();

    /**
     * Finish the action normally and release the context.
     */
    void complete();

    /**
     * Cancel the action and release the context.
     */
    void cancel();
}
