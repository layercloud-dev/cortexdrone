package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.navigation.FlightController;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.NavigateAction;
import ir.layercloud.cortexdrone.action.navigation.impl.StopAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.chunk.impl.ChunkLoadingManager;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import li.cil.oc.common.entity.Drone;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Goal-driven movement manager.
 *
 * <p>The harness sends only {@code goto} or {@code stop}. This manager chooses
 * a flight leg and delegates the actual movement to an action executor.
 * Managers never contain flight or waypoint code; actions do. Breadcrumb
 * backtracking is a service query, not an action.</p>
 *
 * <p>Navigation is self-healing: when a leg is blocked or stalled it rejects
 * the trail, tries detours, and—when the call allows it—keeps tunnelling until
 * {@code max_dig_blocks} is spent. A per-leg watchdog (Baritone's movement
 * timeout) prevents a wedged drone from spinning forever. The model only sees
 * a failure after every recovery attempt is exhausted.</p>
 *
 * <p>It also owns every per-drone navigation field (goal, error, path, stall
 * counters, request token, path-cache signature and tick clock). The controller
 * carries none of them.</p>
 */
public final class NavigationManager implements FlightController {
    private static final double ARRIVAL_RADIUS = 0.85;
    private static final int MAX_FLIGHT_TICKS = 12000;
    /** Absolute recovery ceiling; successful digging resets the soft budget. */
    private static final int MAX_RECOVERIES = 96;
    private static final int MAX_INSTANT_FAILURES = 2;
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final ChunkLoadingManager chunkLoader;
    private final StallReporter stallReporter;
    private final Map<DroneController, NavigationSession> sessions = new ConcurrentHashMap<>();
    private final Map<DroneController, NavigationState> states = new ConcurrentHashMap<>();

    public NavigationManager(AIDroneManager manager, DroneLogger logger,
                             FlightPathfinder pathfinder, BreadcrumbService breadcrumbService,
                             ChunkLoadingManager chunkLoader) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.breadcrumbService = breadcrumbService;
        this.chunkLoader = chunkLoader;
        this.stallReporter = new StallReporter(logger);
    }

    /** Per-drone navigation state, created on first use. */
    public NavigationState state(DroneController controller) {
        if (controller == null) return null;
        return this.states.computeIfAbsent(controller, c -> {
            Drone drone = c.drone;
            double x = drone != null ? drone.posX : 0.0;
            double y = drone != null ? drone.posY : 0.0;
            double z = drone != null ? drone.posZ : 0.0;
            return new NavigationState(x, y, z);
        });
    }

    public NavigationResult gotoGoal(DroneController controller,
                                     NavigationRequest request) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return NavigationResult.failure("No active drone");
        }
        NavigationState state = state(controller);
        NavigationTarget target = NavigationTargetResolver.resolve(request);
        if (target == null) {
            return NavigationResult.failure("goto needs a destination: goto({x,y,z}) or "
                    + "goto({standoffOf:{x,y,z}}); positional args are only x, y, z.");
        }

        NavigationSession previous = this.sessions.remove(controller);
        if (previous != null) {
            cancelLeg(previous);
            state.finishRequest(previous.requestId, "replaced",
                    "Navigation was replaced by a newer goto.");
        }
        state.clearPath();
        state.stuckTicks = 0;
        state.lastPathPartial = false;
        long requestId = state.beginRequest();
        state.goalX = target.getX();
        state.goalY = target.getY();
        state.goalZ = target.getZ();

        NavigationSession session = new NavigationSession();
        session.requestId = requestId;
        session.allowDig = request.isAllowDig();
        session.maxDigBlocks = request.getMaxDigBlocks();
        session.recoveryAttempts = request.getRecoveryAttempts();
        if (target.isBlockApproach()) {
            session.targetBlock = new int[]{
                    request.getStandoffX(), request.getStandoffY(), request.getStandoffZ()};
        }
        session.bestDistance = controller.drone.getDistance(
                target.getX(), target.getY(), target.getZ());
        this.sessions.put(controller, session);
        this.chunkLoader.updateDroneTicket(controller, state);
        return NavigationResult.started(target.getX(), target.getY(), target.getZ(),
                "planning", requestId);
    }

    public NavigationResult stop(DroneController controller, EntityPlayerMP player, String reason) {
        if (controller == null || controller.drone == null) {
            return NavigationResult.failure("No active drone");
        }
        NavigationState state = state(controller);
        NavigationSession session = this.sessions.remove(controller);
        if (session != null) {
            cancelLeg(session);
            state.finishRequest(session.requestId, "stopped", reason != null ? reason : "Navigation stop");
        }
        state.clearPath();
        state.stuckTicks = 0;
        NavigationContext context = new NavigationContext(controller, player,
                NavigationMode.STOP, controller.drone.posX, controller.drone.posY,
                controller.drone.posZ, reason != null ? reason : "Navigation stop");
        new StopAction(state).execute(context);
        // Keep the ticket: releasing it here lets vanilla unload the chunk the
        // idle drone is hovering in. It is trimmed by the normal policy and
        // released on despawn/shutdown.
        this.chunkLoader.updateDroneTicket(controller, state);
        return NavigationResult.stopped(controller.drone.posX, controller.drone.posY,
                controller.drone.posZ);
    }

    /**
     * Called once per server tick from the runtime pipeline.
     */
    public void tick(DroneController controller) {
        if (controller == null || controller.drone == null) return;
        NavigationState state = state(controller);
        state.ticks++;
        if (state.repathCooldown > 0) state.repathCooldown--;

        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null || world.isRemote) return;

        double movedSq = (drone.posX - state.lastX) * (drone.posX - state.lastX)
                + (drone.posY - state.lastY) * (drone.posY - state.lastY)
                + (drone.posZ - state.lastZ) * (drone.posZ - state.lastZ);
        state.lastX = drone.posX;
        state.lastY = drone.posY;
        state.lastZ = drone.posZ;
        state.lastMoveSq = movedSq;
        this.stallReporter.track(controller, state, drone, world, movedSq);

        NavigationSession session = this.sessions.get(controller);
        if (session == null) return;

        // Keep the rolling navigation corridor claimed before the pathfinder
        // reads it. This is also what lets a drone enter a fresh chunk without
        // touching chunks that belong to a player's render distance.
        this.chunkLoader.updateDroneTicket(controller, state);

        session.ticks++;
        if (session.ticks > MAX_FLIGHT_TICKS) {
            fail(controller, "Navigation timed out");
            return;
        }

        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (session.targetBlock != null && TunnelPlanner.inReach(drone,
                session.targetBlock[0], session.targetBlock[1], session.targetBlock[2])) {
            arrive(controller, session);
            return;
        }
        if (session.targetBlock == null && distance <= ARRIVAL_RADIUS) {
            arrive(controller, session);
            return;
        }
        if (session.leg != null) {
            tickLeg(controller, session);
            return;
        }
        if (session.replanCooldown > 0) session.replanCooldown--;
        if (distance < session.bestDistance - 0.5) {
            session.bestDistance = distance;
            session.recoveries = 0;
            session.failedRecoveries = 0;
        }
        if (session.leg == null) session.phase = NavigationSession.Phase.PLAN;
        if (session.phase == NavigationSession.Phase.PLAN) {
            decide(controller, session);
        }
    }
    /**
     * Runs one flight action tick for non-session callers (follow/drop/deposit
     * and mining standoffs). Still executed by {@link NavigateAction}.
     */
    @Override
    public void flyToward(DroneController controller, double x, double y, double z,
                          String label) {
        if (controller == null || controller.drone == null) {
            return;
        }
        NavigationContext context = new NavigationContext(controller, null,
                NavigationMode.FLIGHT, x, y, z, label);
        NavigateAction action = new NavigateAction(this.pathfinder, this.breadcrumbService,
                state(controller));
        try {
            action.tick(context);
        } finally {
            context.complete();
        }
    }
    public boolean isActive(DroneController controller) {
        return controller != null && this.sessions.containsKey(controller);
    }

    public Status status(DroneController controller) {
        NavigationSession session = this.sessions.get(controller);
        if (session == null) return Status.idle();
        NavigationState state = state(controller);
        return new Status(true, session.phase.name().toLowerCase(), session.reason,
                state.goalX, state.goalY, state.goalZ);
    }

    public void clear(DroneController controller) {
        NavigationSession session = this.sessions.get(controller);
        if (session != null) {
            cancelLeg(session);
            state(controller).finishRequest(session.requestId, "cancelled", "Navigation cleared.");
        }
        this.sessions.remove(controller);
        NavigationState state = state(controller);
        if (state != null) {
            state.clearPath();
            state.stuckTicks = 0;
        }
        // Keep the drone's own chunk claimed while it idles; the ticket is
        // released by forget() on despawn and by shutdown() on server stop.
        if (controller != null) {
            this.chunkLoader.updateDroneTicket(controller, state);
        }
    }

    /** Drops all per-drone navigation state when the controller goes away. */
    public void forget(DroneController controller) {
        clear(controller);
        this.chunkLoader.releaseDroneTicket(controller);
        this.states.remove(controller);
    }

    private void decide(DroneController controller, NavigationSession session) {
        if (session.replanCooldown > 0) return;

        NavigationState state = state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

        // A standoff request whose target is buried: keep clearing the line to
        // the block instead of pretending the solid block itself is a waypoint.
        if (session.targetBlock != null) {
            int[] obstruction = TunnelPlanner.nextObstruction(world, drone,
                    session.targetBlock[0] + 0.5, session.targetBlock[1] + 0.5,
                    session.targetBlock[2] + 0.5, session.targetBlock);
            if (obstruction != null) {
                session.reason = "tunnel-to-block";
                if (startDig(controller, session, obstruction)) return;
            }
            session.reason = "approach";
            startLeg(controller, session, state.goalX, state.goalY, state.goalZ,
                    "Approach block");
            return;
        }

        // Fast path: a clear fat-ray to the goal needs no trail lookup, no A*
        // expansion and no recovery. This is the common case outdoors and it is
        // what makes navigation feel instant.
        if (FlightPathfinder.isFlightLineClear(world, drone.posX, drone.posY, drone.posZ,
                state.goalX, state.goalY, state.goalZ)) {
            session.usingTrail = false;
            session.reason = "direct";
            startLeg(controller, session, state.goalX, state.goalY, state.goalZ, "Direct flight");
            return;
        }

        // Once tunnelling has started, keep tunnelling: a trail backtrack would
        // fly away from the hole being dug and reset the session's progress.
        if (!session.trailRejected && session.dug == 0) {
            TrailPlan trail = this.breadcrumbService.planBacktrack(controller,
                    state.goalX, state.goalY, state.goalZ);
            if (trail != null && trail.isUseful()) {
                Breadcrumb endpoint = trail.getEndpoint();
                session.usingTrail = true;
                session.reason = "trail-" + trail.getReason();
                startLeg(controller, session,
                        endpoint.getX() + 0.5, endpoint.getY() + 0.65, endpoint.getZ() + 0.5,
                        "Trail endpoint (" + trail.getReason() + ")");
                this.logger.log("NAV", controller, "Flying to trail endpoint (" + trail.getReason() + ")",
                        "point=(" + endpoint.getX() + "," + endpoint.getY() + "," + endpoint.getZ() + ")");
                return;
            }
        }

        session.usingTrail = false;
        session.reason = "flight";
        startLeg(controller, session,
                state.goalX, state.goalY, state.goalZ, "Flight");
    }

    private void startLeg(DroneController controller, NavigationSession session,
                          double targetX, double targetY, double targetZ, String label) {
        session.phase = NavigationSession.Phase.FLIGHT;
        NavigationContext context = new NavigationContext(controller, null, NavigationMode.FLIGHT,
                targetX, targetY, targetZ, label);
        NavigateAction action = new NavigateAction(this.pathfinder,
                this.breadcrumbService, state(controller));
        action.execute(context);
        // The action has just planned a fresh path; claim its corridor before
        // vanilla can evict chunks that are not near a player.
        this.chunkLoader.updateDroneTicket(controller, state(controller));
        if (context.isActive()) {
            context.retain();
            session.leg = new NavigationSession.Leg(action, context);
            session.watchdog.begin(distance(controller.drone, targetX, targetY, targetZ));
            session.lastLegMoved = false;
        } else {
            session.leg = null;
            handleResult(controller, session, context, context.result());
        }
    }

    private void tickLeg(DroneController controller, NavigationSession session) {
        NavigationSession.Leg leg = session.leg;
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.context, leg.context.result());
            return;
        }
        leg.action.tick(leg.context);
        NavigationState state = state(controller);
        Drone drone = controller.drone;
        if (state.lastMoveSq >= 0.0025) session.lastLegMoved = true;
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.context, leg.context.result());
            return;
        }
        double toTarget = leg.targetDistance(drone.posX, drone.posY, drone.posZ);
        if (session.watchdog.tick(toTarget, state.lastMoveSq)) {
            session.leg = null;
            leg.action.cancel(leg.context);
            session.failedRecoveries = session.lastLegMoved ? 0 : session.failedRecoveries + 1;
            recover(controller, session, "Flight leg stalled");
            return;
        }
        NavigateResult result = leg.context.result();
        if (result != null) handleProgress(controller, session, result);
    }

    private void handleProgress(DroneController controller, NavigationSession session,
                                NavigateResult result) {
        if (result.isBlocked()) {
            cancelLeg(session);
            session.failedRecoveries = session.lastLegMoved ? 0 : session.failedRecoveries + 1;
            recover(controller, session, result.getError() != null
                    ? result.getError() : "Flight path is blocked");
        } else if (result.isLegDone()) {
            cancelLeg(session);
            if (session.digTarget != null) {
                // Reached the standoff of the obstructing voxel: clear it now.
                digPending(controller, session);
            } else if (session.targetBlock != null
                    && !TunnelPlanner.inReach(controller.drone,
                    session.targetBlock[0], session.targetBlock[1], session.targetBlock[2])) {
                session.phase = NavigationSession.Phase.PLAN;
                session.replanCooldown = 1;
            } else {
                session.phase = NavigationSession.Phase.PLAN;
                session.replanCooldown = 2;
            }
        }
    }

    private void handleResult(DroneController controller, NavigationSession session,
                              NavigationContext context, NavigateResult result) {
        if (result == null) {
            fail(controller, "Navigation action produced no result");
            return;
        }
        if (result.getError() != null) {
            fail(controller, result.getError());
            return;
        }
        switch (context.mode()) {
            case STOP:
                this.sessions.remove(controller);
                return;
            case FLIGHT:
            default:
                if (result.isBlocked()) {
                    session.failedRecoveries = session.lastLegMoved ? 0 : session.failedRecoveries + 1;
                    recover(controller, session, "Flight path is blocked");
                } else if (result.isLegDone()) {
                    if (session.digTarget != null) {
                        digPending(controller, session);
                    } else {
                        session.phase = NavigationSession.Phase.PLAN;
                        session.replanCooldown = 2;
                    }
                }
        }
    }

    /**
     * Automatic recovery. The order is deliberate:
     *
     * <ol>
     *   <li>keep a tunnel already in progress (do not backtrack away from it);</li>
     *   <li>two failed legs in a row mean detours are not working: dig now;</li>
     *   <li>reject the trail once, then try detours (cheap, no world change);</li>
     *   <li>dig one voxel toward the goal, vertically if the floor/ceiling is
     *       the only way;</li>
     *   <li>fail only when the dig budget or the absolute recovery ceiling is
     *       exhausted.</li>
     * </ol>
     */
    private void recover(DroneController controller, NavigationSession session, String reason) {
        NavigationState state = state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

        if (session.digTarget != null) {
            int[] pending = session.digTarget;
            if (TunnelPlanner.inReach(drone, pending[0], pending[1], pending[2])) {
                digPending(controller, session);
                return;
            }
            session.rejectedDigs.add(FlightPathfinder.packBlockPos(
                    pending[0], pending[1], pending[2]));
            session.digTarget = null;
        }

        session.recoveries++;
        if (session.recoveries > MAX_RECOVERIES) {
            fail(controller, reason + " (recovery ceiling reached)");
            return;
        }

        if (session.allowDig && (session.dug > 0
                || session.failedRecoveries >= MAX_INSTANT_FAILURES)) {
            if (resumeOpenTunnel(controller, session, state, world)) return;
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
                startLeg(controller, session, detour[0], detour[1], detour[2], "Detour");
                return;
            }
        }

        if (session.allowDig) {
            if (session.dug >= session.maxDigBlocks) {
                fail(controller, reason + " (dig budget " + session.maxDigBlocks + " exhausted)");
                return;
            }
            // Detours were already tried above; only now clear the obstruction
            // or take a vertical step toward the goal.
            if (tryDig(controller, session, state, world, drone)) return;
            if (tryDigVertical(controller, session, state, world, drone)) return;
            if (startDig(controller, session,
                    TunnelPlanner.nextLocalObstruction(world, drone, state.goalX, state.goalZ))) return;
            fail(controller, reason + " (no tunnel direction found)");
            return;
        }

        fail(controller, reason + " (this goto call disabled digging)");
    }

    /** Keeps an already-open tunnel going instead of backtracking away from it. */
    private boolean resumeOpenTunnel(DroneController controller, NavigationSession session,
                                     NavigationState state, World world) {
        return continueDigging(controller, session, state, world);
    }

    /** Tries the straight obstruction first, then a vertical step. */
    private boolean continueDigging(DroneController controller, NavigationSession session,
                                    NavigationState state, World world) {
        if (session.dug >= session.maxDigBlocks) return false;
        if (tryDig(controller, session, state, world, controller.drone)) return true;
        return tryDigVertical(controller, session, state, world, controller.drone);
    }

    /** Mines one step up or down toward a goal that is far above/below the drone. */
    private boolean tryDigVertical(DroneController controller, NavigationSession session,
                                   NavigationState state, World world, Drone drone) {
        int[] target = TunnelPlanner.nextVertical(world, drone,
                CortexDroneMod.findPlayer(controller.ownerName),
                state.goalX, state.goalY, state.goalZ);
        return startDig(controller, session, target);
    }

    /** Finds the obstruction toward the goal and either digs it or flies to its standoff. */
    private boolean tryDig(DroneController controller, NavigationSession session,
                           NavigationState state, World world, Drone drone) {
        int[] target = session.digTarget;
        if (target == null) {
            target = TunnelPlanner.nextObstruction(world, drone,
                    state.goalX, state.goalY, state.goalZ, session.targetBlock);
        }
        return startDig(controller, session, target);
    }

    /** Starts digging {@code target}, or flies to its reachable standoff. */
    private boolean startDig(DroneController controller, NavigationSession session, int[] target) {
        if (target == null) return false;
        if (session.targetBlock != null && sameBlock(target, session.targetBlock)) return false;
        World world = controller.drone.worldObj;
        EntityPlayerMP player = CortexDroneMod.findPlayer(controller.ownerName);
        if (!TunnelPlanner.safeToDig(world, player, target[0], target[1], target[2])) return false;
        long key = FlightPathfinder.packBlockPos(target[0], target[1], target[2]);
        if (session.rejectedDigs.contains(key)) return false;

        Drone drone = controller.drone;
        if (TunnelPlanner.inReach(drone, target[0], target[1], target[2])) {
            session.digTarget = target;
            digPending(controller, session);
            return true;
        }

        double[] standoff = FlightPathfinder.findBlockStandoff(world, drone,
                target[0], target[1], target[2]);
        if (standoff == null) return false;
        session.digTarget = target;
        session.reason = "tunnel";
        startLeg(controller, session, standoff[0], standoff[1], standoff[2], "Tunnel standoff");
        return true;
    }

    /** Breaks the pending obstruction, then replans the route. */
    private void digPending(DroneController controller, NavigationSession session) {
        NavigationState state = state(controller);
        Drone drone = controller.drone;
        int[] target = session.digTarget;
        session.digTarget = null;
        if (target == null) {
            session.phase = NavigationSession.Phase.PLAN;
            session.replanCooldown = 1;
            return;
        }
        BlockResult result = this.manager.getBuildingManager().handle(
                controller, CortexDroneMod.findPlayer(controller.ownerName), "break_block",
                BlockRequest.builder()
                        .x(target[0])
                        .y(target[1])
                        .z(target[2])
                        .build());
        if (result == null || !result.isOk()) {
            session.rejectedDigs.add(FlightPathfinder.packBlockPos(
                    target[0], target[1], target[2]));
            session.phase = NavigationSession.Phase.PLAN;
            session.replanCooldown = 1;
            return;
        }
        session.dug++;
        session.recoveries = 0;
        session.failedRecoveries = 0;
        // Journal only blocks that actually disappeared, so repair_trail never
        // records a refused or still-present voxel.
        this.manager.getDamageJournal().record(controller, drone.worldObj,
                target[0], target[1], target[2], "navigation");
        this.logger.log("NAV", controller, "Auto-tunnelling",
                String.format("broke %s at (%d,%d,%d) dug=%d/%d",
                        result.getBlock(), target[0], target[1], target[2],
                        session.dug, session.maxDigBlocks));
        state.clearPath();
        state.stuckTicks = 0;
        session.phase = NavigationSession.Phase.PLAN;
        session.replanCooldown = 1;
    }

    private double[] nextDetour(NavigationSession session, NavigationState state,
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

    private static double distance(Drone drone, double x, double y, double z) {
        return drone.getDistance(x, y, z);
    }

    private static void cancelLeg(NavigationSession session) {
        if (session.leg != null && session.leg.context.isActive()) {
            session.leg.action.cancel(session.leg.context);
        }
        session.leg = null;
    }

    private void arrive(DroneController controller, NavigationSession session) {
        NavigationState state = state(controller);
        this.sessions.remove(controller);
        session.watchdog.begin(0.0);
        state.finishRequest(session.requestId, "arrived", "");
        // The drone is still an entity in this chunk; keep it force-loaded
        // until despawn or the normal policy releases it.
        this.chunkLoader.updateDroneTicket(controller, state);
        state.stuckTicks = 0;
        state.lastPathPartial = false;
        state.clearPath();
        if (session.targetBlock != null) {
            state.goalX = session.targetBlock[0] + 0.5;
            state.goalY = session.targetBlock[1] + 0.5;
            state.goalZ = session.targetBlock[2] + 0.5;
        }
        controller.drone.lightColor_$eq(0x00FFAA);
        controller.drone.statusText_$eq("Arrived\n"
                + (int) Math.floor(state.goalX) + "," + (int) Math.floor(state.goalZ));
    }

    private void fail(DroneController controller, String reason) {
        if (controller == null) return;
        NavigationSession session = this.sessions.remove(controller);
        if (session != null) {
            cancelLeg(session);
            state(controller).finishRequest(session.requestId, "failed", reason);
        }
        this.chunkLoader.updateDroneTicket(controller, state(controller));
        NavigationState state = state(controller);
        state.error = reason;
        state.clearPath();
        state.stuckTicks = 0;
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.logger.log("NAV", controller, "Navigation failed", reason);
    }
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Status {
        private final boolean active;
        private final String phase;
        private final String reason;
        private final double goalX;
        private final double goalY;
        private final double goalZ;

        public static Status idle() {
            return new Status(false, "idle", "", 0, 0, 0);
        }
    }
}
