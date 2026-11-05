package ir.layercloud.cortexdrone.action.interact;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Context for one interact/use primitive. The work happens inside the RPC
 * call, so the context is not retained.
 */
public final class InteractionActionContext extends ActionContextBase {
    private int[] blockTarget;
    private int entityTarget = -1;

    public InteractionActionContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public void targetBlock(int x, int y, int z) {
        this.blockTarget = new int[]{x, y, z};
    }

    public void targetEntity(int entityId) {
        this.entityTarget = entityId;
    }

    public int[] blockTarget() { return this.blockTarget; }
    public int entityTarget() { return this.entityTarget; }
}
