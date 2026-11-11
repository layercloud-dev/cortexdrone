package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;

/**
 * Executes the block break for one automatic-tunnelling step.
 *
 * <p>Extracted from {@link NavigationManager} to keep that class inside its
 * modular line budget. The class owns only the side effects of a dig: the
 * break call, the rejection bookkeeping, the damage journal and the log line.
 * Choosing the next target stays in {@link TunnelRecovery}.</p>
 */
final class TunnelDigger {
    private final AIDroneManager manager;
    private final DroneLogger logger;

    TunnelDigger(AIDroneManager manager, DroneLogger logger) {
        this.manager = manager;
        this.logger = logger;
    }

    /**
     * Breaks {@code target}, then asks navigation to replan.
     *
     * @return true when the block actually disappeared
     */
    boolean dig(DroneController controller, NavigationSession session,
                NavigationState state, int[] target) {
        Drone drone = controller.drone;
        BlockResult result = this.manager.getBuildingManager().handle(
                controller, CortexDroneMod.findPlayer(controller.ownerName), "break_block",
                BlockRequest.builder()
                        .x(target[0])
                        .y(target[1])
                        .z(target[2])
                        .build());
        if (result == null || !result.isOk()) {
            session.rejectedDigs.add(FlightPathfinder.packBlockPos(
                    target[0], target[1], target[2]));
            return false;
        }
        session.dug++;
        session.recoveries = 0;
        session.failedRecoveries = 0;
        TunnelRecovery.trackDigProgress(session, drone, state);
        // Journal only blocks that actually disappeared, so repair_trail never
        // records a refused or still-present voxel.
        this.manager.getDamageJournal().record(controller, drone.worldObj,
                target[0], target[1], target[2], "navigation");
        this.logger.log("NAV", controller, "Auto-tunnelling",
                String.format("broke %s at (%d,%d,%d) dug=%d/%d",
                        result.getBlock(), target[0], target[1], target[2],
                        session.dug, session.maxDigBlocks));
        state.clearPath();
        state.stuckTicks = 0;
        return true;
    }
}
