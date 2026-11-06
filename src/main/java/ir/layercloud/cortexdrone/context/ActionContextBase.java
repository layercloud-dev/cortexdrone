package ir.layercloud.cortexdrone.context;

import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Lombok-backed lifecycle base for action contexts.
 *
 * <p>Controller, player, operation and start tick are immutable. Only the
 * active/retained lifecycle flags and any category-specific state change while
 * the action runs.</p>
 */
@Getter
@Accessors(fluent = true)
public abstract class ActionContextBase implements ActionContext {
    private final DroneController controller;
    private final EntityPlayerMP player;
    private final String operation;
    private final long startedTick;

    @Getter(AccessLevel.NONE)
    private boolean active = true;

    @Getter(AccessLevel.NONE)
    private boolean retained;

    protected ActionContextBase(DroneController controller, EntityPlayerMP player, String operation) {
        this.controller = controller;
        this.player = player;
        this.operation = operation;
        this.startedTick = controller != null ? controller.tickClock : 0L;
    }

    @Override
    public final boolean isActive() {
        return this.active;
    }

    @Override
    public final boolean isRetained() {
        return this.active && this.retained;
    }

    @Override
    public final void retain() {
        if (this.active) this.retained = true;
    }

    @Override
    public final void complete() {
        if (!this.active) return;
        this.active = false;
        this.retained = false;
        onComplete();
    }

    @Override
    public final void cancel() {
        if (!this.active) return;
        this.active = false;
        this.retained = false;
        onCancel();
    }

    /**
     * Called once when the action finishes normally.
     */
    protected void onComplete() {
    }

    /**
     * Called once when the action is cancelled or replaced.
     */
    protected void onCancel() {
    }
}
