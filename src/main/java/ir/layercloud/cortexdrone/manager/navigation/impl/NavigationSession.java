package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable state for one {@code goto} request.
 *
 * <p>The session owns the recovery budget, the current leg and its watchdog.
 * It is package-private on purpose: the navigation manager is the only writer,
 * and {@link LegWatchdog} is the only piece worth testing on its own.</p>
 */
final class NavigationSession {
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

    // Automatic recovery: navigation resolves route failures itself.
    int recoveries;
    int failedRecoveries;
    int detours;
    int dug;
    double bestDistance = Double.MAX_VALUE;
    boolean lastLegMoved;
    boolean allowDig = true;
    int maxDigBlocks = 128;
    int recoveryAttempts = 12;
    int[] digTarget;
    /** A block the caller asked to stand beside; enables tunnel-to-target. */
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
