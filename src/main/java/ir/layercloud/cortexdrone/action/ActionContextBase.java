package ir.layercloud.cortexdrone.action;

import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Base lifecycle implementation for category contexts.
 *
 * <p>A fresh context is active but not retained. Actions that start background
 * work call {@link #retain()}; one-shot actions simply return and the registry
 * discards the context.</p>
 */
public abstract class ActionContextBase implements ActionContext {
    private final DroneController controller;
    private final EntityPlayerMP player;
    private final String operation;
    private final long startedTick;
    private boolean active = true;
    private boolean retained;

    protected ActionContextBase(DroneController controller, EntityPlayerMP player, String operation) {
        this.controller = controller;
        this.player = player;
        this.operation = operation;
        this.startedTick = controller != null ? controller.tickClock : 0L;
    }

    @Override
    public final DroneController controller() { return this.controller; }

    @Override
    public final EntityPlayerMP player() { return this.player; }

    @Override
    public final String operation() { return this.operation; }

    @Override
    public final long startedTick() { return this.startedTick; }

    @Override
    public final boolean isActive() { return this.active; }

    @Override
    public final boolean isRetained() { return this.active && this.retained; }

    @Override
    public final void retain() {
        if (this.active) this.retained = true;
    }

    @Override
    public final void tick() {
        if (this.active) this.onTick();
    }

    @Override
    public final void complete() {
        if (!this.active) return;
        this.active = false;
        this.retained = false;
        this.onComplete();
    }

    @Override
    public final void cancel() {
        if (!this.active) return;
        this.active = false;
        this.retained = false;
        this.onCancel();
    }

    /** Called once per tick while retained. */
    protected void onTick() {
    }

    /** Called once when the operation finishes normally. */
    protected void onComplete() {
    }

    /** Called once when the operation is cancelled or replaced. */
    protected void onCancel() {
    }
}
