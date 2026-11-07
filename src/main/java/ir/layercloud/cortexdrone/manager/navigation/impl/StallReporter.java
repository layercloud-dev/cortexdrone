package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.ClearLeafObstructionAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;

/**
 * Stall diagnostics for one navigation tick.
 *
 * <p>Logging is part of recovery, not decoration: the STUCK line tells the
 * operator whether the drone is wedged on a leaf, a wall or a bad waypoint.
 * The throttle keeps a looping drone from filling the log while the watchdog
 * and recovery work in the background.</p>
 */
@RequiredArgsConstructor
final class StallReporter {
    private final DroneLogger logger;

    void track(DroneController controller, NavigationState state, Drone drone,
               World world, double movedSq) {
        double distToTarget = drone.getDistance(drone.targetX(), drone.targetY(), drone.targetZ());
        if (distToTarget <= 0.70 || movedSq >= 0.0002) {
            state.stuckTicks = 0;
            return;
        }
        state.stuckTicks++;
        if (state.stuckTicks != 18 && state.stuckTicks % 30 != 0) return;
        state.lastStuckReason = FlightPathfinder.describeNearestObstruction(
                world, drone.posX, drone.posY, drone.posZ);
        String stuckKey = controller.id + "|" + state.lastStuckReason
                + "|" + (drone.posX * 4) + "," + (drone.posY * 4) + "," + (drone.posZ * 4)
                + "->" + (int) ((double) drone.targetX() * 4) + ","
                + (int) ((double) drone.targetY() * 4) + ","
                + (int) ((double) drone.targetZ() * 4);
        if (!stuckKey.equals(state.lastStuckKey)
                || state.ticks - state.lastStuckLogTick >= 240) {
            this.logger.log("STUCK", controller,
                    String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            drone.posX, drone.posY, drone.posZ,
                            (double) drone.targetX(), (double) drone.targetY(),
                            (double) drone.targetZ()),
                    "obstruction=" + state.lastStuckReason);
            state.lastStuckKey = stuckKey;
            state.lastStuckLogTick = state.ticks;
        }
        // Leaves and other replaceable plants are cheap to clear and cause the
        // majority of cosmetic stalls; clearing them is not terrain damage in
        // the sense the recovery budget protects.
        NavigationContext context = new NavigationContext(controller, null,
                NavigationMode.FLIGHT, drone.posX, drone.posY, drone.posZ, "Leaf clearance");
        new ClearLeafObstructionAction().execute(context);
    }
}
