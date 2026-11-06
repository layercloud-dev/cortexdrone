package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one interaction action. Interactions are synchronous.
 */
@Getter
@Accessors(fluent = true)
public final class InteractionContext extends ActionContextBase {
    @Setter
    private InteractionResult result;

    private int[] blockTarget;
    private int entityTarget = -1;

    public InteractionContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public void targetBlock(int x, int y, int z) {
        this.blockTarget = new int[]{x, y, z};
    }

    public void targetEntity(int entityId) {
        this.entityTarget = entityId;
    }
}
