package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.*;

/**
 * Read-only owner of the traversal trail.
 *
 * <p>The service records where each drone has actually travelled and answers
 * questions about the trail. It never changes mode, goals or navigation state;
 * the navigation manager decides whether a returned trail is useful.</p>
 */
public final class BreadcrumbService {
    private static final int MAX_POINTS = 256;
    private static final int OBSERVE_INTERVAL_TICKS = 4;
    private static final int MAX_BACKTRACK = 64;
    private static final int MIN_BACKTRACK = 4;

    private final Map<DroneController, Trail> trails =
            new IdentityHashMap<DroneController, Trail>();

    /**
     * Samples the drone position. Call once per server tick; the service throttles itself.
     */
    public void observe(DroneController controller, World world) {
        if (controller == null || controller.drone == null || world == null) return;
        Trail trail = trail(controller);
        if (trail.cooldown > 0) {
            trail.cooldown--;
            return;
        }
        int x = MathHelper.floor_double(controller.drone.posX);
        int y = MathHelper.floor_double(controller.drone.posY);
        int z = MathHelper.floor_double(controller.drone.posZ);
        if (!ItemClassifier.isPassableVoxel(world, x, y, z)) return;
        trail.cooldown = OBSERVE_INTERVAL_TICKS;
        record(trail, x, y, z, world.canBlockSeeTheSky(x, y, z));
    }

    public List<Breadcrumb> snapshot(DroneController controller, int limit) {
        Trail trail = this.trails.get(controller);
        if (trail == null) return Collections.emptyList();
        int max = Math.min(MAX_POINTS, Math.max(MIN_BACKTRACK, limit));
        List<Breadcrumb> all = new ArrayList<Breadcrumb>(trail.points);
        int start = Math.max(0, all.size() - max);
        return new ArrayList<Breadcrumb>(all.subList(start, all.size()));
    }

    public Breadcrumb surfaceAnchor(DroneController controller) {
        Trail trail = this.trails.get(controller);
        return trail != null ? trail.surfaceAnchor : null;
    }

    public int count(DroneController controller) {
        Trail trail = this.trails.get(controller);
        return trail != null ? trail.points.size() : 0;
    }

    public void clear(DroneController controller) {
        this.trails.remove(controller);
    }

    /**
     * Suggests the furthest useful point on the recorded trail.
     *
     * <p>It compares three candidates inside at most half of the trail (capped):
     * the first point with a clear flight line to the target, the first point
     * that has open sky, and the point closest to the target. The navigation
     * manager still decides whether to follow any of them.</p>
     */
    public TrailPlan planBacktrack(DroneController controller, double targetX,
                                   double targetY, double targetZ) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        Trail trail = this.trails.get(controller);
        if (trail == null || trail.points.size() < 2) return null;

        World world = controller.drone.worldObj;
        List<Breadcrumb> history = new ArrayList<Breadcrumb>(trail.points);
        int maxSteps = Math.min(MAX_BACKTRACK, Math.max(MIN_BACKTRACK, history.size() / 2));
        maxSteps = Math.min(maxSteps, history.size() - 1);

        double currentX = controller.drone.posX;
        double currentY = controller.drone.posY;
        double currentZ = controller.drone.posZ;
        if (VoxelRaycaster3D.isFatLineClear(world, currentX, currentY, currentZ,
                targetX, targetY, targetZ)) {
            return null; // already flyable; no trail detour needed
        }
        double currentToTarget = distance(currentX, currentY, currentZ, targetX, targetY, targetZ);
        int targetHeight = world.getHeightValue(MathHelper.floor_double(targetX),
                MathHelper.floor_double(targetZ));
        boolean targetAtOrAboveSurface = targetY >= targetHeight - 3;
        boolean currentUnderground = !world.canBlockSeeTheSky(
                MathHelper.floor_double(currentX), MathHelper.floor_double(currentY),
                MathHelper.floor_double(currentZ));

        TrailPlan reconnect = null;
        TrailPlan surface = null;
        TrailPlan goalward = null;
        double goalwardDistance = Double.MAX_VALUE;

        for (int step = 1; step <= maxSteps; step++) {
            int index = history.size() - 1 - step;
            if (index < 0) break;
            Breadcrumb point = history.get(index);
            if (!ItemClassifier.isPassableVoxel(world, point.getX(), point.getY(), point.getZ())) {
                continue;
            }
            List<Breadcrumb> waypoints = waypointsTo(history, index);
            if (waypoints.isEmpty()) continue;
            double trailCost = pathLength(world, currentX, currentY, currentZ, waypoints);
            double pointToTarget = distance(point.getX() + 0.5, point.getY() + 0.65,
                    point.getZ() + 0.5, targetX, targetY, targetZ);
            double progress = currentToTarget - pointToTarget;

            if (reconnect == null && FlightLine.clear(world, point, targetX, targetY, targetZ)) {
                reconnect = new TrailPlan(waypoints, point, "reconnect");
            }
            if (surface == null && point.isSkyVisible()) {
                surface = new TrailPlan(waypoints, point, "surface");
            }
            if (pointToTarget < goalwardDistance) {
                goalwardDistance = pointToTarget;
                goalward = new TrailPlan(waypoints, point, "goalward");
            }

            // Stop as soon as the trail no longer makes progress away from the target.
            if (progress < -2.0 && trailCost > 8.0) break;
        }

        if (currentUnderground && targetAtOrAboveSurface && surface != null) {
            return surface;
        }
        if (reconnect != null && progressOf(currentToTarget, reconnect, targetX, targetY, targetZ) >= -1.0) {
            return reconnect;
        }
        if (goalward != null
                && progressOf(currentToTarget, goalward, targetX, targetY, targetZ) > 1.5) {
            return goalward;
        }
        return null;
    }

    private static double progressOf(double currentToTarget, TrailPlan plan,
                                     double targetX, double targetY, double targetZ) {
        Breadcrumb endpoint = plan.getEndpoint();
        double pointToTarget = distance(endpoint.getX() + 0.5, endpoint.getY() + 0.65,
                endpoint.getZ() + 0.5, targetX, targetY, targetZ);
        return currentToTarget - pointToTarget;
    }

    private static List<Breadcrumb> waypointsTo(List<Breadcrumb> history, int endpointIndex) {
        List<Breadcrumb> waypoints = new ArrayList<Breadcrumb>();
        for (int i = history.size() - 2; i >= endpointIndex; i--) {
            waypoints.add(history.get(i));
        }
        return waypoints;
    }

    private static double pathLength(World world, double sx, double sy, double sz,
                                     List<Breadcrumb> waypoints) {
        double length = 0.0;
        double px = sx;
        double py = sy;
        double pz = sz;
        for (Breadcrumb point : waypoints) {
            double x = point.getX() + 0.5;
            double y = point.getY() + 0.65;
            double z = point.getZ() + 0.5;
            length += Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
            px = x;
            py = y;
            pz = z;
        }
        return length;
    }

    private void record(Trail trail, int x, int y, int z, boolean skyVisible) {
        if (skyVisible) {
            trail.surfaceAnchor = new Breadcrumb(x, y, z, true);
        }
        Breadcrumb last = trail.points.peekLast();
        if (last != null) {
            int dx = x - last.getX();
            int dy = y - last.getY();
            int dz = z - last.getZ();
            if (dx * dx + dy * dy + dz * dz < 4) return;
        }

        List<Breadcrumb> snapshot = new ArrayList<Breadcrumb>(trail.points);
        int searchStart = Math.max(0, snapshot.size() - 28);
        for (int i = searchStart; i < snapshot.size() - 1; i++) {
            Breadcrumb point = snapshot.get(i);
            if (Math.abs(point.getX() - x) <= 1
                    && Math.abs(point.getY() - y) <= 1
                    && Math.abs(point.getZ() - z) <= 1) {
                int toPop = snapshot.size() - 1 - i;
                for (int p = 0; p < toPop; p++) trail.points.pollLast();
                return;
            }
        }

        trail.points.addLast(new Breadcrumb(x, y, z, skyVisible));
        while (trail.points.size() > MAX_POINTS) trail.points.pollFirst();
    }

    private Trail trail(DroneController controller) {
        Trail trail = this.trails.get(controller);
        if (trail == null) {
            trail = new Trail();
            this.trails.put(controller, trail);
        }
        return trail;
    }

    private static double distance(double ax, double ay, double az,
                                   double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class Trail {
        private final Deque<Breadcrumb> points = new ArrayDeque<Breadcrumb>();
        private Breadcrumb surfaceAnchor;
        private int cooldown;
    }

    /**
     * Keeps the service free of a dependency on the manager-oriented path classes.
     */
    private static final class FlightLine {
        private static boolean clear(World world, Breadcrumb point,
                                     double targetX, double targetY, double targetZ) {
            return VoxelRaycaster3D.isFatLineClear(world,
                    point.getX() + 0.5, point.getY() + 0.65, point.getZ() + 0.5,
                    targetX, targetY, targetZ);
        }
    }
}
