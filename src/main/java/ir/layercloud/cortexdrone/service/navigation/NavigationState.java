package ir.layercloud.cortexdrone.service.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable navigation state for one drone.
 *
 * <p>Owned by {@code NavigationManager}. The controller no longer carries any
 * goal, path, stall or path-cache fields; actions and services receive this
 * object instead.</p>
 */
public final class NavigationState {
    /**
     * Last requested/active goal. Kept at the current position on stop/idle.
     */
    public double goalX;
    public double goalY;
    public double goalZ;
    public volatile String error = "";

    /**
     * Monotonic request token and its final outcome. A waiter must compare
     * {@link #finishedRequestId} with the token returned by {@code goto},
     * otherwise a replace/stop from another task looks like a successful
     * arrival (the old session simply disappeared).
     */
    public final AtomicLong nextRequestId = new AtomicLong();
    public volatile long finishedRequestId;
    public volatile String finishedOutcome = "";
    public volatile String finishedError = "";

    /**
     * Current A* path and the goal it was planned for.
     */
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

    /**
     * Path cache signature (world revision + endpoint bucket).
     */
    public long cachedPathSignature = Long.MIN_VALUE;
    public long cachedPathCreatedTick = -1L;

    /**
     * Stall detection and throttled logging.
     */
    public int stuckTicks = 0;
    public String lastStuckReason = "";
    public String lastStuckKey = "";
    public int lastStuckLogTick = -10000;

    /**
     * Per-waypoint blocked counters: an A/B/A/B alternation of two blocked goals
     * defeats a single last-key throttle, so hits are counted per waypoint.
     */
    public final Map<Long, Integer> solidWaypointHits = new HashMap<>();

    /**
     * Per-drone tick clock used by the navigation cache/log throttles.
     */
    public int ticks = 0;

    /**
     * Last sampled drone position and movement delta, used for stall detection.
     */
    public double lastX;
    public double lastY;
    public double lastZ;
    public double lastMoveSq;
    /**
     * Flight-speed multiplier of the active request (1.0 = tuned speed). Kept
     * here because the arrival and waypoint tolerances must grow with the
     * closing speed, and the action only receives this object.
     */
    public double speedMultiplier = 1.0;

    public NavigationState(double goalX, double goalY, double goalZ) {
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.lastX = goalX;
        this.lastY = goalY;
        this.lastZ = goalZ;
    }

    /**
     * Moves the logical goal to the position the drone currently holds.
     */
    public void hold(double x, double y, double z) {
        this.goalX = x;
        this.goalY = y;
        this.goalZ = z;
    }

    /**
     * Starts a new request and returns its token.
     */
    public long beginRequest() {
        long id = this.nextRequestId.incrementAndGet();
        this.error = "";
        return id;
    }

    /**
     * Marks a request finished exactly once; later callers keep the first outcome.
     */
    public void finishRequest(long requestId, String outcome, String error) {
        if (requestId <= 0L || requestId <= this.finishedRequestId) return;
        // Write payload first, id last: a reader that observes the new id is
        // guaranteed to see the matching outcome/error (volatile ordering).
        this.finishedOutcome = outcome != null ? outcome : "";
        this.finishedError = error != null ? error : "";
        this.finishedRequestId = requestId;
    }

    public boolean isRequestFinished(long requestId) {
        return requestId > 0L && this.finishedRequestId >= requestId;
    }

    /**
     * Clears the active path without touching the logical goal.
     */
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
