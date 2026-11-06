package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: break exactly one block. The block is gone when the call
 * returns; there is no retained break and no progress to tick.
 */
@RequiredArgsConstructor
public final class BreakBlockAction implements Action<BlockContext> {
    private final BuildingManager buildingManager;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        String broken = this.buildingManager.breakBlock(
                context.controller(), this.request.getX(), this.request.getY(), this.request.getZ());
        context.result(broken != null
                ? BlockResult.broken(broken, this.request.getX(), this.request.getY(), this.request.getZ())
                : BlockResult.failure("Cannot break block at "
                        + this.request.getX() + "," + this.request.getY() + "," + this.request.getZ()
                        + " (not mineable, already air, or no drone)"));
    }
}
