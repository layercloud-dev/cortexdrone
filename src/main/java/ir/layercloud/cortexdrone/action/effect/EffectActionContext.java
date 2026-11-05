package ir.layercloud.cortexdrone.action.effect;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Context for one LED/particle/sound/chat primitive. Effects are applied
 * immediately; the context exists only for the duration of the RPC call.
 */
public final class EffectActionContext extends ActionContextBase {
    private String effect;

    public EffectActionContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public void effect(String effect) { this.effect = effect; }
    public String effect() { return this.effect; }
}
