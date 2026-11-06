package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one block action. A break is retained until the controller
 * stops mining that exact block; place completes inside the call.
 */
@Getter
@Accessors(fluent = true)
public final class BlockContext extends ActionContextBase {
    @Setter
    private BlockResult result;

    private int[] target;
    private boolean breakOperation;
    private int ticks;

    public BlockContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public void trackBreak(int x, int y, int z) {
        this.target = new int[]{x, y, z};
        this.breakOperation = true;
        retain();
    }

    @Override
    protected void onTick() {
        DroneController controller = controller();
        if (!this.breakOperation || controller == null || controller.drone == null
            || controller.drone.isDead || controller.mode != Mode.MINE
            || controller.currentMineTarget == null || !matches(controller.currentMineTarget)) {
            complete();
            return;
        }
        this.ticks++;
    }

    private boolean matches(int[] current) {
        return this.target != null && current != null
            && current[0] == this.target[0]
            && current[1] == this.target[1]
            && current[2] == this.target[2];
    }
}
