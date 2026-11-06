package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: break exactly one explicit block and tick it to
 * completion. No mode, no queue: the action itself advances the break.
 */
@RequiredArgsConstructor
public final class BreakBlockAction implements Action<BlockContext> {
    private final BuildingManager buildingManager;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        boolean started = this.buildingManager.startBreak(
                context.controller(), this.request.getX(), this.request.getY(), this.request.getZ());
        context.result(BlockResult.breakStarted(started,
                this.request.getX(), this.request.getY(), this.request.getZ()));
        if (started) {
            context.trackBreak(this.request.getX(), this.request.getY(), this.request.getZ());
        }
    }

    @Override
    public void tick(BlockContext context) {
        DroneController controller = context.controller();
        if (!context.breakOperation() || controller == null || controller.drone == null
                || controller.drone.isDead
                || !context.matchesTarget(controller.currentMineTarget)) {
            context.complete();
            return;
        }
        this.buildingManager.tickMining(controller);
        if (!context.matchesTarget(controller.currentMineTarget)) {
            context.complete();
        }
    }
}
