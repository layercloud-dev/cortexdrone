package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.RiderGuard;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;

/**
 * Per-tick runtime pipeline.
 *
 * <p>It owns no behavior: it samples breadcrumbs, advances navigation (which
 * owns stall detection and path state), ticks every retained action (including
 * the light aura and LED animation), and applies the physics safety step.
 * Everything the drone actually does is an action started by a tool call.</p>
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
        this.manager.getInteractionManager().tickCooldown(ctrl);
        NavigationState navigation = this.navigationManager.state(ctrl);

        // OC hardware bridge is expensive (reflection + buffer top-ups); amortize every 40 ticks per drone.
        boolean machineRunningOk = (world.getTotalWorldTime() % 40L == 1L)
                ? this.manager.ensureMachineRunningState(d) : d.isRunning();

        // The breadcrumb service owns sampling, dedupe and loop pruning. Navigation
        // owns stall detection, path cooldowns and the per-drone tick clock.
        this.breadcrumbService.observe(ctrl, world);
        this.navigationManager.tick(ctrl);
        // Retained action contexts drive mining, deposit and delivery work.
        this.manager.getActionRegistry().tick(ctrl);
        holdWhenIdle(ctrl, d);

        DroneSession session = this.manager.getSessionManager().session(ctrl);
        if (session != null && session.isActive()
                && session.activeScriptCount() == 0
                && !session.hasActiveTaskOrLoop(0)) {
            session.complete();
        }

        double postTargetDist = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        double distFinalGoal = navigation == null || Double.isNaN(navigation.goalX)
                ? postTargetDist
                : d.getDistance(navigation.goalX, navigation.goalY, navigation.goalZ);
        boolean longRangeFlight = distFinalGoal > 14.0;
        if (!machineRunningOk || (postTargetDist > 0.30 && (navigation == null || navigation.lastMoveSq < 0.0004)) || longRangeFlight) {
            // Speed comes from DroneSpeed: the tuned tiers scaled by the
            // configured multiplier, hard-capped at 5x. A carried rider never
            // gets a boost because the extra closing speed is lethal under a
            // low ceiling.
            boolean carrying = RiderGuard.isCarrying(d);
            double maxSpeed = DroneSpeed.resolve(distFinalGoal,
                    DroneSpeed.configured(this.manager.getConfig()),
                    this.navigationManager.speedMultiplier(ctrl), carrying);
            FlightPathfinder.runServerSidePhysicsStep(d, maxSpeed);
        }
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
