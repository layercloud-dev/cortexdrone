package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one block action.
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

    public boolean matchesTarget(int[] current) {
        return this.target != null && current != null
                && current[0] == this.target[0]
                && current[1] == this.target[1]
                && current[2] == this.target[2];
    }

    public void countTick() {
        this.ticks++;
    }
}
