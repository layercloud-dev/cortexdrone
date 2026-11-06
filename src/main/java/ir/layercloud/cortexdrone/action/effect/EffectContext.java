package ir.layercloud.cortexdrone.action.effect;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/** Mutable state for one effect action. Effects complete synchronously. */
@Getter
@Accessors(fluent = true)
public final class EffectContext extends ActionContextBase {
    @Setter
    private EffectResult result;

    public EffectContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }
}
