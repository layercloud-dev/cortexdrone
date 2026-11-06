package ir.layercloud.cortexdrone.service.effect.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import ir.layercloud.cortexdrone.service.effect.impl.LightingAura;
import ir.layercloud.cortexdrone.action.effect.impl.PlaySoundAction;
import ir.layercloud.cortexdrone.action.effect.impl.SayAction;
import ir.layercloud.cortexdrone.action.effect.impl.SetLedAction;
import ir.layercloud.cortexdrone.action.effect.impl.SetStatusTextAction;
import ir.layercloud.cortexdrone.action.effect.impl.SpawnParticlesAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.effect.impl.EffectContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived effect manager. It creates one single-use action per effect
 * request and owns the domain lighting aura. JSON stays in EffectRpcTranslator.
 */
public final class EffectService extends ActionService<EffectContext, EffectRequest, EffectResult> {
    private final AIDroneManager manager;
    private final LightingAura lightingAura;

    public EffectService(AIDroneManager manager, DroneLogger logger) {
        super("effect", "set_led", "led_effect", "spawn_particles",
            "play_sound", "play_note", "say", "set_status_text");
        this.manager = manager;
        this.lightingAura = new LightingAura(logger);
    }

    @Override
    protected EffectContext createContext(DroneController controller, EntityPlayerMP player,
                                          String operation) {
        return new EffectContext(controller, player, operation);
    }

    @Override
    protected Action<EffectContext> createAction(EffectContext context, String operation,
                                                 EffectRequest request) {
        if ("set_led".equals(operation) || "led_effect".equals(operation)) {
            return new SetLedAction(request);
        }
        if ("spawn_particles".equals(operation)) return new SpawnParticlesAction(request);
        if ("play_sound".equals(operation) || "play_note".equals(operation)) {
            return new PlaySoundAction(request);
        }
        if ("say".equals(operation)) return new SayAction(this.manager, request);
        if ("set_status_text".equals(operation)) return new SetStatusTextAction(request);
        throw new IllegalArgumentException("Unsupported effect action: " + operation);
    }

    @Override
    protected EffectResult result(EffectContext context) {
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
