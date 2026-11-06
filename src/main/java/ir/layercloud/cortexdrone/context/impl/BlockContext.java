package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one block action. Both block primitives are one-shot, so
 * the context only carries the result.
 */
@Getter
@Accessors(fluent = true)
public final class BlockContext extends ActionContextBase {
    @Setter
    private BlockResult result;

    public BlockContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }
}
