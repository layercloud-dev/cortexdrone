package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one visuals action. Visuals complete synchronously.
 */
@Getter
@Accessors(fluent = true)
public final class VisualsContext extends ActionContextBase {
    @Setter
    private VisualsResult result;

    public VisualsContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }
}
