package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.*;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.List;

/**
 * Executes one no-dig flight leg. It only plans and follows safe waypoints; it
 * reports {@code blocked} when the flight path is blocked or partial so the
 * navigation manager can choose the next action.
 */
@RequiredArgsConstructor
public final class NavigateAction implements Action<NavigationContext> {
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final NavigationState state;

    @Override
    public void execute(NavigationContext context) {
        tick(context);
        if (context.isActive()) context.retain();
    }

    @Override
    public void tick(NavigationContext context) {
        DroneController ctrl = context.controller();
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        Drone drone = ctrl.drone;
        World world = drone.worldObj;

        int curBx = MathHelper.floor_double(drone.posX);
        int curBy = MathHelper.floor_double(drone.posY);
        int curBz = MathHelper.floor_double(drone.posZ);
        int topCur = ChunkAccess.ensureBlockLoaded(world, curBx, curBz)
                ? world.getHeightValue(curBx, curBz) : curBy;
        boolean inOpenSky = world.canBlockSeeTheSky(curBx, curBy, curBz)
                || drone.posY >= topCur - 1;
        int goalBx = MathHelper.floor_double(context.targetX());
        int goalBz = MathHelper.floor_double(context.targetZ());
        int topGoal = ChunkAccess.ensureBlockLoaded(world, goalBx, goalBz)
                ? world.getHeightValue(goalBx, goalBz) : (int) Math.floor(drone.posY);
        boolean goalOnSurface = context.targetY() >= topGoal - NavigationTuning.SURFACE_TOLERANCE;

        double gx = context.targetX();
        double gy = context.targetY();
        double gz = context.targetZ();
        double effGx = gx;
        double effGy = gy;
        double effGz = gz;
        double horizDist = Math.hypot(gx - drone.posX, gz - drone.posZ);

        if (!inOpenSky && goalOnSurface && (gy - drone.posY > NavigationTuning.SURFACE_DETOUR_HEIGHT
                || horizDist > NavigationTuning.SURFACE_DETOUR_DISTANCE)) {
            Breadcrumb anchor = this.breadcrumbService.surfaceAnchor(ctrl);
            boolean anchorUsable = false;
            if (anchor != null
                    && Math.hypot(anchor.getX() - drone.posX, anchor.getZ() - drone.posZ)
                    <= NavigationTuning.SURFACE_ANCHOR_RADIUS
                    && ItemClassifier.isPassableVoxel(world, anchor.getX(), anchor.getY(), anchor.getZ())
                    && ItemClassifier.isPassableVoxel(world, anchor.getX(), anchor.getY() + 1, anchor.getZ())) {
                effGx = anchor.getX() + DroneGeometry.CELL_CENTER;
                effGy = anchor.getY() + NavigationTuning.ANCHOR_HEIGHT_OFFSET;
                effGz = anchor.getZ() + DroneGeometry.CELL_CENTER;
                anchorUsable = true;
            }
            if (!anchorUsable) {
                int[] openAbove = FlightPathfinder.findNearestPassableVoxel(
                        world, curBx, Math.min(WorldBounds.MAX_LANDING_Y, topCur + 2), curBz, 4);
                if (openAbove != null) {
                    effGx = openAbove[0] + DroneGeometry.CELL_CENTER;
                    effGy = openAbove[1] + DroneGeometry.AIM_OFFSET;
                    effGz = openAbove[2] + DroneGeometry.CELL_CENTER;
                } else {
                    effGx = curBx + DroneGeometry.CELL_CENTER;
                    effGy = Math.min(WorldBounds.MAX_LANDING_Y, topCur + NavigationTuning.ESCAPE_HEIGHT_OFFSET);
                    effGz = curBz + DroneGeometry.CELL_CENTER;
                }
            }
        } else if (horizDist > DroneSpeed.strideTrigger(inOpenSky ? NavigationTuning.STRIDE_TRIGGER_OPEN_SKY
                        : NavigationTuning.STRIDE_TRIGGER_SHELTERED,
                this.state.speedMultiplier)) {
            // The stride must cover the braking distance at the current speed,
            // otherwise the drone enters ease-in, decelerates, and starts a new
            // sub-leg before it ever reaches cruise. That is what capped a 5x
            // drone at ~20 b/s even though its cap was 75 b/s.
            double step = DroneSpeed.stride(this.state.speedMultiplier,
                    inOpenSky ? NavigationTuning.STRIDE_STEP_OPEN_SKY
                            : NavigationTuning.STRIDE_STEP_SHELTERED);
            effGx = drone.posX + (gx - drone.posX) / horizDist * step;
            effGz = drone.posZ + (gz - drone.posZ) / horizDist * step;
            if (inOpenSky) {
                // Sample the whole stride, not only its end. At 5x the stride is
                // up to 80 blocks and a mountain in the middle would be missed
                // by an endpoint-only height check.
                int topSub = highestOnStride(world, drone.posX, drone.posZ,
                        effGx, effGz, curBy);
                effGy = Math.max(gy, Math.max(topSub, topCur) + NavigationTuning.STRIDE_CLEARANCE);
            } else {
                effGy = drone.posY + (gy - drone.posY) * (step / horizDist);
            }
        }

        double distGoalMove = Double.isNaN(this.state.pathGoalX) ? NavigationTuning.NO_GOAL_SENTINEL : Math.sqrt(
                (effGx - this.state.pathGoalX) * (effGx - this.state.pathGoalX)
                        + (effGy - this.state.pathGoalY) * (effGy - this.state.pathGoalY)
                        + (effGz - this.state.pathGoalZ) * (effGz - this.state.pathGoalZ));

        boolean needsRepath = this.state.pathWaypoints.isEmpty()
                || (this.state.pathIndex >= this.state.pathWaypoints.size() && this.state.repathCooldown <= 0)
                || (distGoalMove > NavigationTuning.REPATH_DISTANCE && this.state.repathCooldown <= 0)
                || (this.state.stuckTicks >= NavigationTuning.STUCK_REPATH_TICKS && this.state.repathCooldown <= 0);
        if (needsRepath) {
            List<double[]> waypoints = this.pathfinder.findFlightPath(
                    world, ctrl, this.state,
                    drone.posX, drone.posY, drone.posZ, effGx, effGy, effGz);
            this.state.pathWaypoints.clear();
            this.state.pathWaypoints.addAll(waypoints);
            this.state.pathIndex = 0;
            this.state.pathGoalX = effGx;
            this.state.pathGoalY = effGy;
            this.state.pathGoalZ = effGz;
            this.state.repathCooldown = DroneSpeed.repathTicks(horizDist > NavigationTuning.REPATH_NEAR_DISTANCE
                            ? NavigationTuning.REPATH_COOLDOWN_NEAR
                            : NavigationTuning.REPATH_COOLDOWN_FAR,
                    this.state.speedMultiplier);
        }

        while (this.state.pathIndex < this.state.pathWaypoints.size()) {
            double[] waypoint = this.state.pathWaypoints.get(this.state.pathIndex);
            double distWp = drone.getDistance(waypoint[0], waypoint[1], waypoint[2]);
            boolean last = this.state.pathIndex == this.state.pathWaypoints.size() - 1;
            double distWpToGoal = Math.sqrt(
                    (waypoint[0] - effGx) * (waypoint[0] - effGx)
                            + (waypoint[1] - effGy) * (waypoint[1] - effGy)
                            + (waypoint[2] - effGz) * (waypoint[2] - effGz));
            if (distWp <= DroneSpeed.waypointRadius(this.state.speedMultiplier) && !last) {
                this.state.pathIndex++;
            } else if (distWp <= DroneSpeed.waypointRadius(this.state.speedMultiplier)
                    && (this.state.lastPathPartial || distWpToGoal > NavigationTuning.WAYPOINT_RETARGET_DISTANCE)
                    && this.state.repathCooldown <= 0) {
                this.state.pathIndex++;
                break;
            } else {
                break;
            }
        }

        boolean exhausted = this.state.pathIndex >= this.state.pathWaypoints.size();
        double[] activeWp = exhausted
                ? new double[]{effGx, effGy, effGz}
                : this.state.pathWaypoints.get(this.state.pathIndex);
        int awx = MathHelper.floor_double(activeWp[0]);
        int away = MathHelper.floor_double(activeWp[1]);
        int awz = MathHelper.floor_double(activeWp[2]);
        if (!ItemClassifier.isPassableVoxel(world, awx, away, awz)
                || !ItemClassifier.isPassableVoxel(world, awx, away + NavigationTuning.BODY_HEIGHT, awz)) {
            FlightPathfinder.setDroneFlightTarget(drone, drone.posX, drone.posY, drone.posZ, NavigationTuning.STOP_DELTA);
            drone.motionX *= NavigationTuning.BRAKE_DAMPING;
            drone.motionY *= NavigationTuning.BRAKE_DAMPING;
            drone.motionZ *= NavigationTuning.BRAKE_DAMPING;
            this.state.stuckTicks = Math.max(this.state.stuckTicks, NavigationTuning.BLOCKED_STUCK_TICKS);
            this.state.clearPath();
            context.result(NavigateResult.blocked(this.state.lastPathPartial));
            context.complete();
            return;
        }

        this.state.solidWaypointHits.remove(FlightPathfinder.packBlockPos(awx, away, awz));
        FlightPathfinder.setDroneFlightTarget(drone, activeWp[0], activeWp[1], activeWp[2], NavigationTuning.WAYPOINT_DELTA);
        context.countTick();

        if (drone.getDistance(context.targetX(), context.targetY(), context.targetZ())
                <= DroneSpeed.arrivalRadius(this.state.speedMultiplier)) {
            context.result(NavigateResult.done());
            context.complete();
            return;
        }
        if (exhausted && this.state.lastPathPartial) {
            context.result(NavigateResult.blocked(true));
            context.complete();
            return;
        }
        context.result(NavigateResult.moved(this.state.lastPathPartial));
    }

    /**
     * Highest terrain column along a horizontal segment.
     *
     * <p>A long high-speed stride must clear every hill between the drone and
     * the sub-goal, not just the one at its far end. Sampling every 8 blocks is
     * enough for the 4.5-block clearance margin and keeps the cost tiny.</p>
     */
    private static int highestOnStride(World world, double fromX, double fromZ,
                                       double toX, double toZ, int fallback) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(length / NavigationTuning.STRIDE_SAMPLE_STEP));
        int top = fallback;
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            int bx = MathHelper.floor_double(fromX + dx * t);
            int bz = MathHelper.floor_double(fromZ + dz * t);
            if (!ChunkAccess.ensureBlockLoaded(world, bx, bz)) continue;
            top = Math.max(top, world.getHeightValue(bx, bz));
        }
        return top;
    }
}
