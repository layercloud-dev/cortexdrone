package ir.layercloud.cortexdrone.manager.navigation.impl;

/**
 * Per-leg stuck detector inspired by Baritone's {@code PathExecutor}.
 *
 * <p>Baritone cancels a movement that takes much longer than its estimated
 * cost ({@code ticksOnCurrent > cost + movementTimeoutTicks}) and a path that
 * stays too far from its executor for too long. The drone equivalent needs two
 * independent guards because it can fail in two ways:</p>
 *
 * <ul>
 *   <li>it stops moving at all (a wall corner, a clipped waypoint);</li>
 *   <li>it keeps moving but makes no progress toward the waypoint
 *       (oscillation between two detours).</li>
 * </ul>
 *
 * <p>The watchdog never decides where to go; the navigation manager only asks
 * it whether the current leg should be abandoned.</p>
 */
final class LegWatchdog {
    /** 2 s without any displacement: the leg is physically wedged. */
    static final int STUCK_TICKS = 40;
    /** 10 s without reducing the waypoint distance by 0.25: the leg is looping. */
    static final int NO_PROGRESS_TICKS = 200;
    /** Hard ceiling derived from the initial leg length (60 ticks per block). */
    static final int MAX_TICKS_PER_BLOCK = 60;
    static final int MIN_MAX_TICKS = 160;
    static final int MAX_MAX_TICKS = 2400;

    private int ticks;
    private int stuckTicks;
    private int noProgressTicks;
    private double initialDistance;
    private double bestDistance;
    private int maxTicks = MIN_MAX_TICKS;

    void begin(double distanceToTarget) {
        this.ticks = 0;
        this.stuckTicks = 0;
        this.noProgressTicks = 0;
        this.initialDistance = Math.max(0.0, distanceToTarget);
        this.bestDistance = this.initialDistance;
        long budget = (long) Math.ceil(this.initialDistance * MAX_TICKS_PER_BLOCK);
        this.maxTicks = (int) Math.max(MIN_MAX_TICKS, Math.min(MAX_MAX_TICKS, budget));
    }

    /**
     * @param distanceToTarget current distance to the leg waypoint
     * @param movedSq          squared displacement since the previous tick
     * @return true when the leg must be abandoned and recovered
     */
    boolean tick(double distanceToTarget, double movedSq) {
        this.ticks++;
        if (movedSq < 0.0016) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        if (distanceToTarget < this.bestDistance - 0.25) {
            this.bestDistance = distanceToTarget;
            this.noProgressTicks = 0;
        } else {
            this.noProgressTicks++;
        }
        return this.stuckTicks >= STUCK_TICKS
                || this.noProgressTicks >= NO_PROGRESS_TICKS
                || this.ticks >= this.maxTicks;
    }

    int ticks() {
        return this.ticks;
    }

    double bestDistance() {
        return this.bestDistance;
    }

    double initialDistance() {
        return this.initialDistance;
    }
}
