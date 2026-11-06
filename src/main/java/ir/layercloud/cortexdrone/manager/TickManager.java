package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;

/**
 * Per-tick runtime pipeline.
 *
 * <p>It owns no behavior: it samples breadcrumbs, detects stalls, runs the
 * active navigation session, ticks every retained action (including the light
 * aura and LED animation), and applies the physics safety step. Everything the
 * drone actually does is an action started by a tool call.</p>
 */
@RequiredArgsConstructor
public class TickManager {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final NavigationManager navigationManager;
    private final BreadcrumbService breadcrumbService;

    public void tickController(DroneController ctrl) {
        Drone d = ctrl.drone;
        World world = d.worldObj;
        if (world == null || world.isRemote) return;
        ctrl.tickClock++;
        if (ctrl.attackCooldown > 0) ctrl.attackCooldown--;
        if (ctrl.repathCooldown > 0) ctrl.repathCooldown--;

        double movedSq = (d.posX - ctrl.lastX) * (d.posX - ctrl.lastX)
                + (d.posY - ctrl.lastY) * (d.posY - ctrl.lastY)
                + (d.posZ - ctrl.lastZ) * (d.posZ - ctrl.lastZ);
        ctrl.lastX = d.posX;
        ctrl.lastY = d.posY;
        ctrl.lastZ = d.posZ;

        // OC hardware bridge is expensive (reflection + buffer top-ups); amortize every 40 ticks per drone
        boolean machineRunningOk = (ctrl.tickClock % 40 == 1)
                ? this.manager.ensureMachineRunningState(d) : d.isRunning();

        // The breadcrumb service owns sampling, dedupe and loop pruning.
        this.breadcrumbService.observe(ctrl, world);

        trackStalls(ctrl, d, world, movedSq);

        this.navigationManager.tick(ctrl);
        // Retained action contexts drive mining, deposit and delivery work.
        this.manager.getActionRegistry().tick(ctrl);
        holdWhenIdle(ctrl, d);

        if (ctrl.task.isActive() && ctrl.activeScriptCount.get() == 0 && !ctrl.hasActiveTaskOrLoop(0)) {
            ctrl.task.complete();
        }

        double postTargetDist = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        double distFinalGoal = Double.isNaN(ctrl.goalX) ? postTargetDist : d.getDistance(ctrl.goalX, ctrl.goalY, ctrl.goalZ);
        boolean longRangeFlight = distFinalGoal > 14.0;
        if (!machineRunningOk || (postTargetDist > 0.30 && movedSq < 0.0004) || longRangeFlight) {
            double maxSpeed = distFinalGoal > 24.0 ? 1.15 : (longRangeFlight ? 0.85 : 0.42);
            FlightPathfinder.runServerSidePhysicsStep(d, maxSpeed, ctrl);
        }
    }

    private void trackStalls(DroneController ctrl, Drone d, World world, double movedSq) {
        double distToTarget = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        if (distToTarget <= 0.70 || movedSq >= 0.0002) {
            ctrl.stuckTicks = 0;
            return;
        }
        ctrl.stuckTicks++;
        if (ctrl.stuckTicks != 18 && ctrl.stuckTicks % 30 != 0) return;
        ctrl.lastStuckReason = FlightPathfinder.describeNearestObstruction(world, d.posX, d.posY, d.posZ);
        // Throttle identical STUCK spam: same obstruction + same waypoint = log once per 240 ticks (12s).
        String stuckKey = ctrl.id + "|" + ctrl.lastStuckReason + "|" + (d.posX * 4) + "," + (d.posY * 4) + "," + (d.posZ * 4)
                + "->" + (int) ((double) d.targetX() * 4) + "," + (int) ((double) d.targetY() * 4) + "," + (int) ((double) d.targetZ() * 4);
        if (!stuckKey.equals(ctrl.lastStuckKey) || ctrl.tickClock - ctrl.lastStuckLogTick >= 240) {
            this.logger.log("STUCK", ctrl,
                    String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            d.posX, d.posY, d.posZ, (double) d.targetX(), (double) d.targetY(), (double) d.targetZ()),
                    "obstruction=" + ctrl.lastStuckReason);
            ctrl.lastStuckKey = stuckKey;
            ctrl.lastStuckLogTick = ctrl.tickClock;
        }
        FlightPathfinder.clearNearbyLeafObstruction(world, ctrl);
    }

    /**
     * Stops an idle drone from coasting toward a stale flight target left behind
     * by the previous action. This is a safety reset, not a behavior mode.
     */
    private void holdWhenIdle(DroneController ctrl, Drone d) {
        if (this.navigationManager.isActive(ctrl)
                || this.manager.getActionRegistry().activeContextCount(ctrl) > 0) {
            return;
        }
        double speedSq = d.motionX * d.motionX + d.motionY * d.motionY + d.motionZ * d.motionZ;
        boolean offTarget = d.getDistance(d.targetX(), d.targetY(), d.targetZ()) > 0.05;
        if (speedSq < 0.0001 && !offTarget) return;
        FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.06f);
        d.motionX *= 0.25;
        d.motionY *= 0.25;
        d.motionZ *= 0.25;
    }

}
