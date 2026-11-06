package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.action.block.impl.BlockContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;

/** Single-use command: break exactly one explicit block. */
@RequiredArgsConstructor
public final class BreakBlockAction implements Action<BlockContext> {
    private final AIDroneManager manager;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        boolean started = start(context.controller(),
            this.request.getX(), this.request.getY(), this.request.getZ());
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
            || controller.drone.isDead || controller.mode != Mode.MINE
            || controller.currentMineTarget == null
            || !context.matchesTarget(controller.currentMineTarget)) {
            context.complete();
            return;
        }
        context.countTick();
    }

    private boolean start(DroneController controller, int x, int y, int z) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return false;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return false;

        if (controller.currentMineTarget != null) {
            world.destroyBlockInWorldPartially(drone.getEntityId(),
                controller.currentMineTarget[0], controller.currentMineTarget[1],
                controller.currentMineTarget[2], -1);
        }
        controller.clearSelfRescue(false);
        controller.mineQueue.clear();
        controller.currentMineTarget = new int[]{x, y, z};

        double[] standoff = FlightPathfinder.findMiningStandoff(world, drone, x, y, z);
        if (standoff == null && drone.getDistance(x + 0.5, y + 0.5, z + 0.5) <= 2.2) {
            standoff = new double[]{drone.posX, drone.posY, drone.posZ};
        }
        controller.currentMineStandoff = standoff;
        controller.mineFilter = "all";
        controller.mineExactQueueOnly = true;
        controller.mineTargetCount = 0;
        controller.mineMatchedCount = 0;
        controller.minedCount = 0;
        controller.returnToOwnerAfterMine = false;
        controller.mineUntilFull = false;
        controller.mineProgressTicks = 0;
        controller.stuckTicks = 0;
        controller.lastPathPartial = false;
        controller.pathWaypoints.clear();
        controller.pathIndex = 0;
        controller.repathCooldown = 0;

        this.manager.setDroneMode(controller, Mode.MINE,
            "Master break_block (" + x + "," + y + "," + z + ")");
        drone.lightColor_$eq(0x00FF66);
        drone.statusText_$eq("Break block\n" + x + "," + y + "," + z);
        return true;
    }
}
