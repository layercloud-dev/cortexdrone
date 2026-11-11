package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * The tunnel/detour escalation used when a flight leg fails.
 *
 * <p>Extracted from {@link NavigationManager} because recovery is a policy of
 * its own: it decides whether to keep an open tunnel, reject the trail, detour,
 * dig, escape a pocket, or give up. Keeping it separate means the manager stays
 * a session/leg orchestrator and the escalation order can be read in one place.</p>
 *
 * <p>The escalation order is deliberate:</p>
 * <ol>
 *   <li>keep a tunnel already in progress (do not backtrack away from it);</li>
 *   <li>two failed legs in a row mean detours are not working: dig now;</li>
 *   <li>reject the trail once, then try detours (cheap, no world change);</li>
 *   <li>dig one voxel toward the goal, vertically if the floor/ceiling is the
 *       only way;</li>
 *   <li>fail only when the dig budget or the absolute recovery ceiling is
 *       exhausted.</li>
 * </ol>
 */
@SuppressWarnings("unused")
final class NavigationRecovery {
    /**
     * Absolute recovery ceiling; successful digging resets the soft budget.
     */
    static final int MAX_RECOVERIES = 96;
    /**
     * Consecutive instant failures before digging is preferred over detours.
     */
    static final int MAX_INSTANT_FAILURES = 2;
    /**
     * Tunnel directions attempted before reporting exhaustion.
     */
    private static final int TUNNEL_ATTEMPTS = 4;

    /**
     * The manager operations recovery needs; keeps the two classes decoupled.
     */
    interface Host {
        NavigationState state(DroneController controller);

        void startLeg(DroneController controller, NavigationSession session,
                      double x, double y, double z, String label);

        void fail(DroneController controller, String reason);

        void digPending(DroneController controller, NavigationSession session);
    }

    private final Host host;
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final StallReporter stallReporter;
    private final TunnelDigger digger;

    NavigationRecovery(Host host, AIDroneManager manager, DroneLogger logger,
                       StallReporter stallReporter, TunnelDigger digger) {
        this.host = host;
        this.manager = manager;
        this.logger = logger;
        this.stallReporter = stallReporter;
        this.digger = digger;
    }

    void recover(DroneController controller, NavigationSession session, String reason) {
        NavigationState state = this.host.state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

        if (session.digTarget != null) {
            int[] pending = session.digTarget;
            if (TunnelPlanner.inReach(drone, pending[0], pending[1], pending[2])) {
                this.host.digPending(controller, session);
                return;
            }
            session.rejectedDigs.add(FlightPathfinder.packBlockPos(
                    pending[0], pending[1], pending[2]));
            session.digTarget = null;
        }

        session.recoveries++;
        if (session.recoveries > MAX_RECOVERIES) {
            this.host.fail(controller, reason + " (recovery ceiling reached)");
            return;
        }

        // A passenger cannot survive a one-wide tunnel: they suffocate, and a
        // low ceiling crushes them. Navigation never digs while carrying one;
        // the caller must open a 2x2 shaft with escape_cave instead.
        if (RiderGuard.blocksTunnel(session)) {
            this.host.fail(controller, RiderGuard.refusal(reason));
            return;
        }

        if (session.allowDig && (session.dug > 0
                || session.failedRecoveries >= MAX_INSTANT_FAILURES)) {
            if (TunnelRecovery.circling(this.logger, controller, session)) {
                session.trailRejected = true;
            } else if (this.resumeOpenTunnel(controller, session, state, world)) {
                return;
            }
        }

        if (session.usingTrail && !session.trailRejected) {
            session.usingTrail = false;
            session.trailRejected = true;
            session.phase = NavigationSession.Phase.PLAN;
            session.replanCooldown = 0;
            return;
        }

        if (session.detours < session.recoveryAttempts) {
            double[] detour = nextDetour(session, state, drone, world);
            if (detour != null) {
                session.reason = "detour-" + session.detours;
                this.logger.log("NAV", controller, "Automatic detour",
                        String.format("point=(%.1f,%.1f,%.1f) reason=%s",
                                detour[0], detour[1], detour[2], reason));
                this.host.startLeg(controller, session, detour[0], detour[1], detour[2], "Detour");
                return;
            }
        }

        if (!session.allowDig) {
            this.host.fail(controller, reason + " (this goto call disabled digging)");
            return;
        }
        if (session.dug >= session.maxDigBlocks) {
            this.host.fail(controller, reason + " (dig budget " + session.maxDigBlocks + " exhausted)");
            return;
        }
        // Detours were already tried above; only now clear the obstruction
        // or take a vertical step toward the goal.
        if (this.tryTunnel(controller, session, state, world, drone)) return;
        // Nothing to mine: a flooded pocket or an open drop still lets the
        // drone fly. Without this step a submerged drone failed with
        // "no tunnel direction found".
        double[] escape = TunnelRecovery.escapeStep(world, drone, state);
        if (escape != null) {
            session.reason = "escape-step";
            this.host.startLeg(controller, session, escape[0], escape[1], escape[2], "Escape step");
            return;
        }
        this.host.fail(controller, reason + " (no tunnel direction found)");
    }

    /**
     * Keeps an already-open tunnel going instead of backtracking away from it:
     * a dug tunnel can face a diagonal wall, so the full escalation is right.
     */
    private boolean resumeOpenTunnel(DroneController controller, NavigationSession session,
                                     NavigationState state, World world) {
        return tryTunnel(controller, session, state, world, controller.drone);
    }

    private boolean tryTunnel(DroneController controller, NavigationSession session,
                              NavigationState state, World world, Drone drone) {
        EntityPlayerMP player = CortexDroneMod.findPlayer(controller.ownerName);
        for (int attempt = 0; attempt < TUNNEL_ATTEMPTS; attempt++) {
            int[] target = TunnelRecovery.nextTarget(state, session, world, drone, player);
            if (target == null) break;
            if (startDig(controller, session, target)) return true;
            session.rejectedDigs.add(FlightPathfinder.packBlockPos(
                    target[0], target[1], target[2]));
        }
        this.stallReporter.tunnelDiagnostics(controller, state, session, world, drone);
        return false;
    }

    /**
     * Starts digging {@code target}, or flies to its reachable standoff.
     */
    boolean startDig(DroneController controller, NavigationSession session, int[] target) {
        if (target == null) return false;
        // The rider guard belongs at the single choke point every dig passes
        // through, not only in recover(): a standoff/tunnel-to-block request
        // must not clear a 1-wide hole under a passenger either.
        if (RiderGuard.blocksTunnel(session)) return false;
        if (session.targetBlock != null && sameBlock(target, session.targetBlock)) return false;
        World world = controller.drone.worldObj;
        EntityPlayerMP player = CortexDroneMod.findPlayer(controller.ownerName);
        if (!TunnelPlanner.safeToDig(world, player, target[0], target[1], target[2])) return false;
        long key = FlightPathfinder.packBlockPos(target[0], target[1], target[2]);
        if (session.rejectedDigs.contains(key)) return false;

        Drone drone = controller.drone;
        if (TunnelPlanner.inReach(drone, target[0], target[1], target[2])) {
            session.digTarget = target;
            this.host.digPending(controller, session);
            return true;
        }

        double[] standoff = FlightPathfinder.findBlockStandoff(world, drone,
                target[0], target[1], target[2]);
        if (standoff == null) return false;
        session.digTarget = target;
        session.reason = "tunnel";
        this.host.startLeg(controller, session, standoff[0], standoff[1], standoff[2], "Tunnel standoff");
        return true;
    }

    /**
     * Breaks the pending obstruction, then replans the route.
     */
    void digPending(DroneController controller, NavigationSession session, NavigationState state) {
        int[] target = session.digTarget;
        session.digTarget = null;
        if (target == null) {
            session.phase = NavigationSession.Phase.PLAN;
            session.replanCooldown = 1;
            return;
        }
        this.digger.dig(controller, session, state, target);
        session.phase = NavigationSession.Phase.PLAN;
        session.replanCooldown = 1;
    }

    private static double[] nextDetour(NavigationSession session, NavigationState state,
                                       Drone drone, World world) {
        int[] attempt = new int[]{session.detours};
        double[] detour = DetourPlanner.next(attempt, session.rejectedDetours,
                state, drone, world);
        session.detours = attempt[0];
        return detour;
    }

    private static boolean sameBlock(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }
}
