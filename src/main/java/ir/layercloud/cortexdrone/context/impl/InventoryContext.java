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
 *
 * <p>Delivery actions retain this context while they fly to their target, so the
 * resolved chest position and the chosen filter live here instead of on the
 * controller.</p>
 */
@Getter
@Accessors(fluent = true)
public final class InventoryContext extends ActionContextBase {
    @Setter
    private InventoryResult result;

    @Setter
    private int[] chestPos;
    @Setter
    private String filter;
    @Setter
    private int movedCount;

    public InventoryContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }
}
