package ir.layercloud.cortexdrone.service.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable navigation state for one drone.
 *
 * <p>Owned by {@code NavigationManager}. The controller no longer carries any
 * goal, path, stall or path-cache fields; actions and services receive this
 * object instead.</p>
 */
public final class NavigationState {
    /** Last requested/active goal. Kept at the current position on stop/idle. */
    public double goalX;
    public double goalY;
    public double goalZ;
    public String error = "";

    /** Current A* path and the goal it was planned for. */
    public final List<double[]> pathWaypoints = new ArrayList<>();
    public int pathIndex = 0;
    public double pathGoalX = Double.NaN;
    public double pathGoalY = Double.NaN;
    public double pathGoalZ = Double.NaN;
    public int repathCooldown = 0;

    public int lastPathNodesExpanded = 0;
    public boolean lastPathPartial = false;
    public String lastPathLogKey = "";
    public int lastPathLogTick = -10000;

    /** Path cache signature (world revision + endpoint bucket). */
    public long cachedPathSignature = Long.MIN_VALUE;
    public long cachedPathCreatedTick = -1L;

    /** Stall detection and throttled logging. */
    public int stuckTicks = 0;
    public String lastStuckReason = "";
    public String lastStuckKey = "";
    public int lastStuckLogTick = -10000;

    /**
     * Per-waypoint blocked counters: an A/B/A/B alternation of two blocked goals
     * defeats a single last-key throttle, so hits are counted per waypoint.
     */
    public final Map<Long, Integer> solidWaypointHits = new HashMap<>();

    /** Per-drone tick clock used by the navigation cache/log throttles. */
    public int ticks = 0;

    /** Last sampled drone position and movement delta, used for stall detection. */
    public double lastX;
    public double lastY;
    public double lastZ;
    public double lastMoveSq;

    public NavigationState(double goalX, double goalY, double goalZ) {
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.lastX = goalX;
        this.lastY = goalY;
        this.lastZ = goalZ;
    }

    /** Moves the logical goal to the position the drone currently holds. */
    public void hold(double x, double y, double z) {
        this.goalX = x;
        this.goalY = y;
        this.goalZ = z;
    }

    /** Clears the active path without touching the logical goal. */
    public void clearPath() {
        this.pathWaypoints.clear();
        this.pathIndex = 0;
        this.pathGoalX = Double.NaN;
        this.pathGoalY = Double.NaN;
        this.pathGoalZ = Double.NaN;
        this.repathCooldown = 0;
        this.lastPathPartial = false;
    }
}
