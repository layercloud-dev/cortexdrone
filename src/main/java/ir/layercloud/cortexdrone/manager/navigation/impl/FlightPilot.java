package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Low-level flight pilot. It only follows safe, no-dig flight paths and reports
 * when a waypoint is blocked; it never digs, changes mode or chooses a route.
 * The navigation manager uses the report to escalate to a dig plan, while
 * follow/drop/deposit use the same pilot for ordinary flying.
 */
public final class FlightPilot {
    private final BreadcrumbService breadcrumbService;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;

    public FlightPilot(BreadcrumbService breadcrumbService, DroneLogger logger,
                       FlightPathfinder pathfinder) {
        this.breadcrumbService = breadcrumbService;
        this.logger = logger;
        this.pathfinder = pathfinder;
    }

    public Outcome flyToward(DroneController ctrl, double gx, double gy, double gz, float accel) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) {
            return Outcome.none();
        }
        Drone d = ctrl.drone;
        World world = d.worldObj;
        NavContext nav = NavContext.of(ctrl, gx, gy, gz, "auto");
        nav.setGoal(gx, gy, gz);

        int curBx = MathHelper.floor_double(d.posX);
        int curBy = MathHelper.floor_double(d.posY);
        int curBz = MathHelper.floor_double(d.posZ);
        int topCur = world.getHeightValue(curBx, curBz);
        boolean droneInOpenSky = world.canBlockSeeTheSky(curBx, curBy, curBz) || d.posY >= topCur - 1;
        int topGoal = world.getHeightValue(MathHelper.floor_double(gx), MathHelper.floor_double(gz));
        boolean goalOnSurface = gy >= topGoal - 3;

        double effGx = gx;
        double effGy = gy;
        double effGz = gz;
        double horizDist = Math.hypot(gx - d.posX, gz - d.posZ);

        if (!droneInOpenSky && goalOnSurface && (gy - d.posY > 6.0 || horizDist > 20.0)) {
            Breadcrumb anchor = this.breadcrumbService.surfaceAnchor(ctrl);
            boolean anchorUsable = false;
            if (anchor != null
                && Math.hypot(anchor.getX() - d.posX, anchor.getZ() - d.posZ) <= 24.0
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
        } else if (horizDist > (droneInOpenSky ? 18.0 : 32.0)) {
            double stepDist = droneInOpenSky ? 16.0 : 24.0;
            effGx = d.posX + (gx - d.posX) / horizDist * stepDist;
            effGz = d.posZ + (gz - d.posZ) / horizDist * stepDist;
            if (droneInOpenSky) {
                int topSub = world.getHeightValue(
                    MathHelper.floor_double(effGx), MathHelper.floor_double(effGz));
                effGy = Math.max(gy, Math.max(topSub, topCur) + 4.5);
            } else {
                effGy = d.posY + (gy - d.posY) * (stepDist / horizDist);
            }
        }

        double distGoalMove = Double.isNaN(ctrl.pathGoalX) ? 999.0 : Math.sqrt(
            (effGx - ctrl.pathGoalX) * (effGx - ctrl.pathGoalX)
                + (effGy - ctrl.pathGoalY) * (effGy - ctrl.pathGoalY)
                + (effGz - ctrl.pathGoalZ) * (effGz - ctrl.pathGoalZ)
        );

        boolean pathEmpty = nav.path().isEmpty();
        boolean pathExhausted = nav.pathIndex() >= nav.path().size();
        boolean needsRepath = pathEmpty
            || (pathExhausted && ctrl.repathCooldown <= 0)
            || (distGoalMove > 0.85 && ctrl.repathCooldown <= 0)
            || (ctrl.stuckTicks >= 12 && ctrl.repathCooldown <= 0);

        if (needsRepath) {
            List<double[]> waypoints = this.pathfinder.findFlightPath(
                world, ctrl, d.posX, d.posY, d.posZ, effGx, effGy, effGz);
            nav.setPath(waypoints, effGx, effGy, effGz);
            ctrl.repathCooldown = horizDist > 18.0 ? 8 : 12;
        }

        while (nav.hasWaypoint()) {
            double[] waypoint = nav.currentWaypoint();
            double distWp = d.getDistance(waypoint[0], waypoint[1], waypoint[2]);
            boolean isLast = nav.pathIndex() == nav.path().size() - 1;
            double distWpToGoal = Math.sqrt(
                (waypoint[0] - effGx) * (waypoint[0] - effGx)
                    + (waypoint[1] - effGy) * (waypoint[1] - effGy)
                    + (waypoint[2] - effGz) * (waypoint[2] - effGz)
            );
            if (distWp <= 0.75 && !isLast) {
                nav.advanceWaypoint();
            } else if (distWp <= 0.75 && isLast
                && (ctrl.lastPathPartial || distWpToGoal > 0.90) && ctrl.repathCooldown <= 0) {
                nav.advanceWaypoint();
                break;
            } else {
                break;
            }
        }

        boolean exhausted = !nav.hasWaypoint();
        double[] activeWp = exhausted
            ? new double[]{effGx, effGy, effGz}
            : nav.currentWaypoint();
        int awx = MathHelper.floor_double(activeWp[0]);
        int away = MathHelper.floor_double(activeWp[1]);
        int awz = MathHelper.floor_double(activeWp[2]);
        if (!ItemClassifier.isPassableVoxel(world, awx, away, awz)
            || !ItemClassifier.isPassableVoxel(world, awx, away + 1, awz)) {
            FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.05f);
            d.motionX *= 0.3;
            d.motionY *= 0.3;
            d.motionZ *= 0.3;
            ctrl.stuckTicks = Math.max(ctrl.stuckTicks, 6);
            nav.clearPath();
            ctrl.repathCooldown = 0;
            logBlocked(ctrl, world, activeWp, awx, away, awz);
            return new Outcome(true, true, true, true, effGx, effGy, effGz);
        }

        ctrl.solidWaypointHits.remove(Long.valueOf(FlightPathfinder.packBlockPos(awx, away, awz)));
        FlightPathfinder.setDroneFlightTarget(d, activeWp[0], activeWp[1], activeWp[2], accel);
        return new Outcome(!nav.path().isEmpty(), ctrl.lastPathPartial, false, exhausted,
            effGx, effGy, effGz);
    }

    public void clear(DroneController controller) {
        if (controller == null) return;
        controller.pathWaypoints.clear();
        controller.pathIndex = 0;
        controller.pathGoalX = Double.NaN;
        controller.pathGoalY = Double.NaN;
        controller.pathGoalZ = Double.NaN;
        controller.repathCooldown = 0;
    }

    private void logBlocked(DroneController ctrl, World world, double[] waypoint,
                            int x, int y, int z) {
        long key = FlightPathfinder.packBlockPos(x, y, z);
        Integer previous = ctrl.solidWaypointHits.get(Long.valueOf(key));
        int hits = previous == null ? 1 : previous.intValue() + 1;
        if (ctrl.solidWaypointHits.size() > 64) ctrl.solidWaypointHits.clear();
        ctrl.solidWaypointHits.put(Long.valueOf(key), Integer.valueOf(hits));
        if (ctrl.tickClock - ctrl.solidLogTick < 80) return;
        Block block = world.getBlock(x, y, z);
        this.logger.log("STUCK", ctrl, "Blocked flight waypoint",
            String.format("wp=(%.2f,%.2f,%.2f) hits=%d block=%s mode=%s",
                waypoint[0], waypoint[1], waypoint[2], hits,
                ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(x, y, z)),
                ctrl.mode.name()));
        ctrl.lastSolidKey = ctrl.id + "|solid:" + key;
        ctrl.lastSolidLogTick = ctrl.tickClock;
        ctrl.solidLogTick = ctrl.tickClock;
    }

    public static final class Outcome {
        private final boolean hasPath;
        private final boolean partial;
        private final boolean blocked;
        private final boolean exhausted;
        private final double targetX;
        private final double targetY;
        private final double targetZ;

        private Outcome(boolean hasPath, boolean partial, boolean blocked,
                        boolean exhausted, double targetX, double targetY, double targetZ) {
            this.hasPath = hasPath;
            this.partial = partial;
            this.blocked = blocked;
            this.exhausted = exhausted;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
        }

        public static Outcome none() {
            return new Outcome(false, false, false, true, 0, 0, 0);
        }

        public boolean hasPath() { return this.hasPath; }
        public boolean isPartial() { return this.partial; }
        public boolean isBlocked() { return this.blocked; }
        public boolean isExhausted() { return this.exhausted; }
        public double getTargetX() { return this.targetX; }
        public double getTargetY() { return this.targetY; }
        public double getTargetZ() { return this.targetZ; }
    }
}
