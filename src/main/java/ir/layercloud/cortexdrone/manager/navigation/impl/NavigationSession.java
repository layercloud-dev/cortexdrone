package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable state for one {@code goto} request.
 *
 * <p>The session owns the recovery budget, the current leg and its watchdog.
 * It is package-private on purpose: the navigation manager is the only writer,
 * and {@link LegWatchdog} is the only piece worth testing on its own.</p>
 */
public class NavigationSession {
    enum Phase {
        PLAN,
        FLIGHT
    }

    long requestId;
    volatile Phase phase = Phase.PLAN;
    Leg leg;
    final LegWatchdog watchdog = new LegWatchdog();

    boolean trailRejected;
    boolean usingTrail;
    int ticks;
    int replanCooldown;
    volatile String reason = "planning";

    /**
     * Session-level progress watchdog. {@link #bestDistance} only resets the
     * recovery budget when a leg finishes; this counter runs every tick so a
     * chain of detours that never approaches the goal cannot run for minutes.
     */
    int noProgressTicks;
    double progressBestDistance = Double.MAX_VALUE;

    /**
     * Where the last recovery escalation started, and how many times in a row a
     * recovery began within a few blocks of it.
     *
     * <p>The live log shows the same spot (a flooded pocket with ladders and a
     * torch) escalating forever: {@code dug=0/32 recov=4 detours=12} repeated
     * every three seconds for twenty minutes, because tiny detour movements kept
     * resetting the per-leg progress counters. A chain of recoveries from one
     * place is not progress, so it is capped explicitly.</p>
     */
    double lastRecoveryX = Double.NaN;
    double lastRecoveryY = Double.NaN;
    double lastRecoveryZ = Double.NaN;
    int sameSpotRecoveries;

    /**
     * Records this recovery's position and reports whether the session has now
     * escalated from the same spot too many times.
     *
     * @param maxRepeats consecutive recoveries allowed from one place
     */
    boolean noteRecoverySpot(double x, double y, double z, int maxRepeats) {
        if (Double.isNaN(this.lastRecoveryX)
                || Math.sqrt(square(x - this.lastRecoveryX)
                + square(y - this.lastRecoveryY)
                + square(z - this.lastRecoveryZ)) > 3.0D) {
            this.lastRecoveryX = x;
            this.lastRecoveryY = y;
            this.lastRecoveryZ = z;
            this.sameSpotRecoveries = 1;
            return false;
        }
        this.sameSpotRecoveries++;
        return this.sameSpotRecoveries > maxRepeats;
    }

    private static double square(double value) {
        return value * value;
    }

    // Automatic recovery: navigation resolves route failures itself.
    int recoveries;
    int failedRecoveries;
    int detours;
    int dug;
    /**
     * Consecutive digs that did not bring the drone closer to the goal.
     * Baritone abandons a movement that costs far more than estimated; here a
     * run of useless digs means the tunnel is heading the wrong way (the live
     * log shows 128 blocks spent zig-zagging in one spot), so recovery
     * re-targets instead of draining the whole budget.
     */
    int uselessDigs;
    double digBestDistance = Double.MAX_VALUE;
    double bestDistance = Double.MAX_VALUE;
    boolean lastLegMoved;
    boolean allowDig = true;
    int maxDigBlocks = NavigationLimits.DEFAULT_DIG_BLOCKS;
    int recoveryAttempts = NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS;
    /**
     * Per-call speed multiplier; 0 = fall back to the configured value.
     */
    double speedMultiplier;
    int[] digTarget;
    /**
     * True when a living entity (a player or a mob) is riding the drone for
     * this request. A rider is 1.8 blocks tall: a one-wide tunnel suffocates
     * them and a low ceiling crushes them, so navigation must never tunnel
     * while carrying one. The caller opens a 2x2 escape shaft instead.
     */
    boolean carryingRider;
    /**
     * A block the caller asked to stand beside; enables tunnel-to-target.
     */
    int[] targetBlock;
    final Set<Long> rejectedDigs = new HashSet<>();
    final Set<Long> rejectedDetours = new HashSet<>();

    static final class Leg {
        final Action<NavigationContext> action;
        final NavigationContext context;

        Leg(Action<NavigationContext> action, NavigationContext context) {
            this.action = action;
            this.context = context;
        }

        double targetDistance(double x, double y, double z) {
            double dx = context.targetX() - x;
            double dy = context.targetY() - y;
            double dz = context.targetZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
