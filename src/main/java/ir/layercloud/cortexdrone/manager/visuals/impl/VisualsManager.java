package ir.layercloud.cortexdrone.manager.visuals.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.action.visuals.impl.*;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-lived visuals manager. Every visual primitive is an action. The manager
 * only creates, runs and retains actions plus the per-drone state they share;
 * the light aura job itself lives in {@link SetLightAction} and
 * {@link LightingAura}.
 */
public final class VisualsManager extends ActionManager<VisualsContext, VisualsRequest, VisualsResult> {
    private final AIDroneManager manager;
    private final LightingAura lightingAura;
    private final Map<DroneController, VisualsState> states = new ConcurrentHashMap<>();

    public VisualsManager(AIDroneManager manager, DroneLogger logger) {
        super("visuals", "set_led", "set_light", "spawn_particles",
                "play_sound", "play_note", "say", "set_status_text");
        this.manager = manager;
        this.lightingAura = new LightingAura(logger);
    }

    /** Per-drone aura state, created on first use. */
    public VisualsState state(DroneController controller) {
        if (controller == null) return null;
        return this.states.computeIfAbsent(controller, ignored -> new VisualsState());
    }

    /** Drops per-drone visual state after restoring the world. */
    public void forget(DroneController controller) {
        clearLighting(controller);
        if (controller != null) this.states.remove(controller);
    }

    @Override
    protected VisualsContext createContext(DroneController controller, EntityPlayerMP player,
                                           String operation) {
        return new VisualsContext(controller, player, operation);
    }

    @Override
    protected Action<VisualsContext> createAction(VisualsContext context, String operation,
                                                  VisualsRequest request) {
        if ("set_led".equals(operation)) {
            return new SetLedAction(request);
        }
        if ("set_light".equals(operation)) {
            return new SetLightAction(this.lightingAura, state(context.controller()), request);
        }
        if ("spawn_particles".equals(operation)) return new SpawnParticlesAction(request);
        if ("play_sound".equals(operation) || "play_note".equals(operation)) {
            return new PlaySoundAction(request);
        }
        if ("say".equals(operation)) return new SayAction(this.manager::droneSay, request);
        if ("set_status_text".equals(operation)) return new SetStatusTextAction(request);
        throw new IllegalArgumentException("Unsupported visuals action: " + operation);
    }

    @Override
    protected VisualsResult result(VisualsContext context) {
        return context.result();
    }

    /**
     * Restarts the retained light aura a reconnected drone had before the
     * restart. LED colour is restored directly on the drone by the caller.
     */
    public void restore(DroneController controller) {
        if (controller == null) return;
        VisualsState state = state(controller);
        if (state.lightAura) {
            VisualsContext context = createContext(controller, null, "set_light");
            VisualsRequest request = VisualsRequest.builder().lightEnabled(Boolean.TRUE).build();
            run(controller, "set_light", context,
                    new SetLightAction(this.lightingAura, state, request));
        }
    }

    public void clearLighting(DroneController controller) {
        if (controller == null) return;
        VisualsContext context = createContext(controller, null, "clear_light");
        run(controller, "clear_light", context,
                new ClearLightAction(this.lightingAura, state(controller)));
    }
}
