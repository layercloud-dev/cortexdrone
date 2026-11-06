package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one inventory action.
 */
@Getter
@Accessors(fluent = true)
public final class InventoryContext extends ActionContextBase {
    @Setter
    private InventoryResult result;

    public InventoryContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }
}
