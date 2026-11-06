package ir.layercloud.cortexdrone.manager.visuals.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.action.visuals.impl.*;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived visuals manager. It creates one single-use action per visuals
 * request and owns the domain lighting aura. JSON stays in VisualsRpcTranslator.
 */
public final class VisualsManager extends ActionManager<VisualsContext, VisualsRequest, VisualsResult> {
    private final AIDroneManager manager;
    private final LightingAura lightingAura;

    public VisualsManager(AIDroneManager manager, DroneLogger logger) {
        super("visuals", "set_led", "led_effect", "spawn_particles",
                "play_sound", "play_note", "say", "set_status_text");
        this.manager = manager;
        this.lightingAura = new LightingAura(logger);
    }

    @Override
    protected VisualsContext createContext(DroneController controller, EntityPlayerMP player,
                                           String operation) {
        return new VisualsContext(controller, player, operation);
    }

    @Override
    protected Action<VisualsContext> createAction(VisualsContext context, String operation,
                                                  VisualsRequest request) {
        if ("set_led".equals(operation) || "led_effect".equals(operation)) {
            return new SetLedAction(request);
        }
        if ("spawn_particles".equals(operation)) return new SpawnParticlesAction(request);
        if ("play_sound".equals(operation) || "play_note".equals(operation)) {
            return new PlaySoundAction(request);
        }
        if ("say".equals(operation)) return new SayAction(this.manager, request);
        if ("set_status_text".equals(operation)) return new SetStatusTextAction(request);
        throw new IllegalArgumentException("Unsupported visuals action: " + operation);
    }

    @Override
    protected VisualsResult result(VisualsContext context) {
        return context.result();
    }

    public LightingAura lighting() {
        return this.lightingAura;
    }

    public void tickLighting(DroneController controller) {
        this.lightingAura.tick(controller);
    }

    public void clearLighting(DroneController controller) {
        this.lightingAura.clearAura(controller);
    }
}
