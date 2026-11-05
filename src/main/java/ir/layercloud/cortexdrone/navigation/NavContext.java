package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.raytrace.ChunkCacheContext;
import java.util.ArrayList;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Immutable snapshot of one navigation request plus the live controller it
 * belongs to.
 *
 * <p>Navigation services take this context instead of reading a dozen mutable
 * {@link DroneController} fields directly. The start/goal/route/cache are
 * captured at request time, which prevents a mid-tick goal change from mixing
 * endpoints from two different requests.</p>
 */
public final class NavContext {
    private final DroneController controller;
    private final Drone drone;
    private final World world;
    private final double startX;
    private final double startY;
    private final double startZ;
    private final double goalX;
    private final double goalY;
    private final double goalZ;
    private final String route;
    private final ChunkCacheContext cache;

    private NavContext(DroneController controller, double goalX, double goalY, double goalZ,
                       String route, int cacheRadius) {
        this.controller = controller;
        this.drone = controller.drone;
        this.world = this.drone != null ? this.drone.worldObj : null;
        this.startX = this.drone != null ? this.drone.posX : Double.NaN;
        this.startY = this.drone != null ? this.drone.posY : Double.NaN;
        this.startZ = this.drone != null ? this.drone.posZ : Double.NaN;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.route = route == null || route.isEmpty() ? "auto" : route;
        this.cache = this.world != null
            ? new ChunkCacheContext(this.world, MathHelper.floor_double(this.startX),
                MathHelper.floor_double(this.startZ), cacheRadius)
            : null;
    }

    public static NavContext of(DroneController controller) {
        return of(controller, controller != null ? controller.goalX : Double.NaN,
            controller != null ? controller.goalY : Double.NaN,
            controller != null ? controller.goalZ : Double.NaN,
            controller != null ? controller.navigationRoute : "auto");
    }

    public static NavContext of(DroneController controller, double gx, double gy, double gz, String route) {
        return new NavContext(controller, gx, gy, gz, route, 3);
    }

    public NavContext withGoal(double gx, double gy, double gz) {
        return new NavContext(this.controller, gx, gy, gz, this.route, 3);
    }

    public DroneController controller() {
        return this.controller;
    }

    public Drone drone() {
        return this.drone;
    }

    public World world() {
        return this.world;
    }

    public double startX() { return this.startX; }
    public double startY() { return this.startY; }
    public double startZ() { return this.startZ; }

    public double goalX() { return this.goalX; }
    public double goalY() { return this.goalY; }
    public double goalZ() { return this.goalZ; }

    public double[] start() { return new double[]{this.startX, this.startY, this.startZ}; }
    public double[] goal() { return new double[]{this.goalX, this.goalY, this.goalZ}; }

    public boolean hasGoal() {
        return !Double.isNaN(this.goalX) && !Double.isNaN(this.goalY) && !Double.isNaN(this.goalZ);
    }

    public String route() {
        return this.route;
    }

    public ChunkCacheContext cache() {
        return this.cache;
    }

    // ------------------------------------------------------------------
    // Live path state (mutating the controller is intentional: the tick
    // runner advances this same path on subsequent ticks)
    // ------------------------------------------------------------------

    public void setGoal(double gx, double gy, double gz) {
        if (this.controller == null) return;
        this.controller.goalX = gx;
        this.controller.goalY = gy;
        this.controller.goalZ = gz;
    }

    public List<double[]> path() {
        return this.controller != null ? this.controller.pathWaypoints : new ArrayList<double[]>();
    }

    public int pathIndex() {
        return this.controller != null ? this.controller.pathIndex : 0;
    }

    public boolean hasWaypoint() {
        return this.controller != null && this.controller.pathIndex < this.controller.pathWaypoints.size();
    }

    public double[] currentWaypoint() {
        return hasWaypoint() ? this.controller.pathWaypoints.get(this.controller.pathIndex) : null;
    }

    public boolean advanceWaypoint() {
        if (this.controller == null || this.controller.pathIndex >= this.controller.pathWaypoints.size()) {
            return false;
        }
        this.controller.pathIndex++;
        return true;
    }

    public void setPath(List<double[]> waypoints, double gx, double gy, double gz) {
        if (this.controller == null) return;
        this.controller.pathWaypoints.clear();
        if (waypoints != null) this.controller.pathWaypoints.addAll(waypoints);
        this.controller.pathIndex = 0;
        this.controller.pathGoalX = gx;
        this.controller.pathGoalY = gy;
        this.controller.pathGoalZ = gz;
    }

    public void clearPath() {
        if (this.controller == null) return;
        this.controller.pathWaypoints.clear();
        this.controller.pathIndex = 0;
    }
}
