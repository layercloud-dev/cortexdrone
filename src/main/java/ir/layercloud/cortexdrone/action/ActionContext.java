package ir.layercloud.cortexdrone.action;

import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one action execution.
 *
 * <p>The service creates the context, the action mutates it, and the service
 * either discards it after the call or retains it for ticking. Once an action
 * completes or is cancelled the context is released and garbage collected.</p>
 */
public interface ActionContext {
    DroneController controller();

    EntityPlayerMP player();

    String operation();

    long startedTick();

    boolean isActive();

    boolean isRetained();

    /** Keeps the context alive across ticks after the call returns. */
    void retain();

    /** Called once per server tick while retained. */
    void tick();

    /** Finish the action normally and release the context. */
    void complete();

    /** Cancel the action and release the context. */
    void cancel();
}
