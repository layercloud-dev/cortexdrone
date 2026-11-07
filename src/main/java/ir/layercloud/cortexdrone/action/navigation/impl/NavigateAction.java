package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
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
        int topCur = world.getHeightValue(curBx, curBz);
        boolean inOpenSky = world.canBlockSeeTheSky(curBx, curBy, curBz)
                || drone.posY >= topCur - 1;
        int topGoal = world.getHeightValue(MathHelper.floor_double(context.targetX()),
                MathHelper.floor_double(context.targetZ()));
        boolean goalOnSurface = context.targetY() >= topGoal - 3;

        double gx = context.targetX();
        double gy = context.targetY();
        double gz = context.targetZ();
        double effGx = gx;
        double effGy = gy;
        double effGz = gz;
        double horizDist = Math.hypot(gx - drone.posX, gz - drone.posZ);

        if (!inOpenSky && goalOnSurface && (gy - drone.posY > 6.0 || horizDist > 20.0)) {
            Breadcrumb anchor = this.breadcrumbService.surfaceAnchor(ctrl);
            boolean anchorUsable = false;
            if (anchor != null
                    && Math.hypot(anchor.getX() - drone.posX, anchor.getZ() - drone.posZ) <= 24.0
                    && ItemClassifier.isPassableVoxel(world, anchor.getX(), anchor.getY(), anchor.getZ())
                    && ItemClassifier.isPassableVoxel(world, anchor.getX(), anchor.getY() + 1, anchor.getZ())) {
                effGx = anchor.getX() + 0.5;
                effGy = anchor.getY() + 1.0;
                effGz = anchor.getZ() + 0.5;
                anchorUsable = true;
            }
            if (!anchorUsable) {
                int[] openAbove = FlightPathfinder.findNearestPassableVoxel(
                        world, curBx, Math.min(250, topCur + 2), curBz, 4);
                if (openAbove != null) {
                    effGx = openAbove[0] + 0.5;
                    effGy = openAbove[1] + 0.35;
                    effGz = openAbove[2] + 0.5;
                } else {
                    effGx = curBx + 0.5;
                    effGy = Math.min(250, topCur + 2.5);
                    effGz = curBz + 0.5;
                }
            }
        } else if (horizDist > (inOpenSky ? 18.0 : 32.0)) {
            double step = inOpenSky ? 16.0 : 24.0;
            effGx = drone.posX + (gx - drone.posX) / horizDist * step;
            effGz = drone.posZ + (gz - drone.posZ) / horizDist * step;
            if (inOpenSky) {
                int topSub = world.getHeightValue(MathHelper.floor_double(effGx),
                        MathHelper.floor_double(effGz));
                effGy = Math.max(gy, Math.max(topSub, topCur) + 4.5);
            } else {
                effGy = drone.posY + (gy - drone.posY) * (step / horizDist);
            }
        }

        double distGoalMove = Double.isNaN(this.state.pathGoalX) ? 999.0 : Math.sqrt(
                (effGx - this.state.pathGoalX) * (effGx - this.state.pathGoalX)
                        + (effGy - this.state.pathGoalY) * (effGy - this.state.pathGoalY)
                        + (effGz - this.state.pathGoalZ) * (effGz - this.state.pathGoalZ));

        boolean needsRepath = this.state.pathWaypoints.isEmpty()
                || (this.state.pathIndex >= this.state.pathWaypoints.size() && this.state.repathCooldown <= 0)
                || (distGoalMove > 0.85 && this.state.repathCooldown <= 0)
                || (this.state.stuckTicks >= 12 && this.state.repathCooldown <= 0);
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
            this.state.repathCooldown = horizDist > 18.0 ? 8 : 12;
        }

        while (this.state.pathIndex < this.state.pathWaypoints.size()) {
            double[] waypoint = this.state.pathWaypoints.get(this.state.pathIndex);
            double distWp = drone.getDistance(waypoint[0], waypoint[1], waypoint[2]);
            boolean last = this.state.pathIndex == this.state.pathWaypoints.size() - 1;
            double distWpToGoal = Math.sqrt(
                    (waypoint[0] - effGx) * (waypoint[0] - effGx)
                            + (waypoint[1] - effGy) * (waypoint[1] - effGy)
                            + (waypoint[2] - effGz) * (waypoint[2] - effGz));
            if (distWp <= 0.75 && !last) {
                this.state.pathIndex++;
            } else if (distWp <= 0.75
                    && (this.state.lastPathPartial || distWpToGoal > 0.90)
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
                || !ItemClassifier.isPassableVoxel(world, awx, away + 1, awz)) {
            FlightPathfinder.setDroneFlightTarget(drone, drone.posX, drone.posY, drone.posZ, 0.05f);
            drone.motionX *= 0.3;
            drone.motionY *= 0.3;
            drone.motionZ *= 0.3;
            this.state.stuckTicks = Math.max(this.state.stuckTicks, 6);
            this.state.clearPath();
            context.result(NavigateResult.blocked(this.state.lastPathPartial));
            context.complete();
            return;
        }

        this.state.solidWaypointHits.remove(FlightPathfinder.packBlockPos(awx, away, awz));
        FlightPathfinder.setDroneFlightTarget(drone, activeWp[0], activeWp[1], activeWp[2], 0.1f);
        context.countTick();

        if (drone.getDistance(context.targetX(), context.targetY(), context.targetZ()) <= 0.85) {
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
}
