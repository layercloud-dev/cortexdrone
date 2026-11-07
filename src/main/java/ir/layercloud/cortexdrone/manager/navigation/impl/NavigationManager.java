package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.navigation.FlightController;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.ClearLeafObstructionAction;
import ir.layercloud.cortexdrone.action.navigation.impl.NavigateAction;
import ir.layercloud.cortexdrone.action.navigation.impl.StopAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Goal-driven movement manager.
 *
 * <p>The harness sends only {@code goto} or {@code stop}. This manager chooses
 * a flight leg and delegates the actual movement to an action executor.
 * Managers never contain flight or waypoint code; actions do. Breadcrumb
 * backtracking is a service query, not an action.</p>
 *
 * <p>Navigation is self-healing: when a leg is blocked it retries the trail,
 * optionally auto-tunnels the obstruction ({@code TunnelPlanner}, gated by the
 * per-call {@code allow_dig} argument) and then tries detours. The model only
 * sees a failure after every recovery attempt is exhausted.</p>
 *
 * <p>It also owns every per-drone navigation field (goal, error, path, stall
 * counters, path-cache signature and tick clock). The controller carries none
 * of them.</p>
 */
public final class NavigationManager implements FlightController {
    private static final double ARRIVAL_RADIUS = 0.85;
    private static final int MAX_FLIGHT_TICKS = 12000;

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final Map<DroneController, Session> sessions = new ConcurrentHashMap<>();
    private final Map<DroneController, NavigationState> states = new ConcurrentHashMap<>();

    public NavigationManager(AIDroneManager manager, DroneLogger logger,
                             FlightPathfinder pathfinder, BreadcrumbService breadcrumbService) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.breadcrumbService = breadcrumbService;
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
        NavigationTarget target = resolveTarget(request);
        if (target == null) {
            return NavigationResult.failure("goto needs a destination: goto({x,y,z}) or "
                    + "goto({standoffOf:{x,y,z}}); positional args are only x, y, z.");
        }

        NavigationState state = state(controller);
        this.sessions.remove(controller);
        state.clearPath();
        state.stuckTicks = 0;
        state.lastPathPartial = false;
        state.error = "";
        state.goalX = target.getX();
        state.goalY = target.getY();
        state.goalZ = target.getZ();

        Session session = new Session();
        session.allowDig = request.isAllowDig();
        session.maxDigBlocks = request.getMaxDigBlocks();
        session.recoveryAttempts = request.getRecoveryAttempts();
        this.sessions.put(controller, session);
        return NavigationResult.started(target.getX(), target.getY(), target.getZ(), "planning");
    }

    public NavigationResult stop(DroneController controller, EntityPlayerMP player, String reason) {
        if (controller == null || controller.drone == null) {
            return NavigationResult.failure("No active drone");
        }
        NavigationState state = state(controller);
        this.sessions.remove(controller);
        state.clearPath();
        state.stuckTicks = 0;
        NavigationContext context = new NavigationContext(controller, player,
                NavigationMode.STOP, controller.drone.posX, controller.drone.posY,
                controller.drone.posZ, reason != null ? reason : "Navigation stop");
        new StopAction(state).execute(context);
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
        trackStalls(controller, state, drone, world, movedSq);

        Session session = this.sessions.get(controller);
        if (session == null) return;

        if (session.leg != null) {
            tickLeg(controller, session);
            return;
        }
        session.ticks++;
        if (session.replanCooldown > 0) session.replanCooldown--;
        if (session.ticks > MAX_FLIGHT_TICKS) {
            fail(controller, "Navigation timed out");
            return;
        }

        double distance = drone.getDistance(state.goalX, state.goalY, state.goalZ);
        if (distance <= ARRIVAL_RADIUS) {
            arrive(controller, session);
            return;
        }
        if (session.phase == Phase.PLAN) {
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
        Session session = this.sessions.get(controller);
        if (session == null) return Status.idle();
        NavigationState state = state(controller);
        return new Status(true, session.phase.name().toLowerCase(), session.reason,
                state.goalX, state.goalY, state.goalZ);
    }

    public void clear(DroneController controller) {
        Session session = this.sessions.get(controller);
        if (session != null && session.leg != null && session.leg.context.isActive()) {
            session.leg.action.cancel(session.leg.context);
        }
        this.sessions.remove(controller);
    }

    /** Drops all per-drone navigation state when the controller goes away. */
    public void forget(DroneController controller) {
        clear(controller);
        this.states.remove(controller);
    }

    private void decide(DroneController controller, Session session) {
        if (session.replanCooldown > 0) return;

        NavigationState state = state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

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

    private void startLeg(DroneController controller, Session session,
                          double targetX, double targetY, double targetZ, String label) {
        session.phase = Phase.FLIGHT;
        NavigationContext context = new NavigationContext(controller, null, NavigationMode.FLIGHT,
                targetX, targetY, targetZ, label);
        Action<NavigationContext> action = new NavigateAction(this.pathfinder,
                this.breadcrumbService, state(controller));
        action.execute(context);
        if (context.isActive()) {
            context.retain();
            session.leg = new Leg(action, context);
        } else {
            session.leg = null;
            handleResult(controller, session, context, context.result());
        }
    }

    private void tickLeg(DroneController controller, Session session) {
        Leg leg = session.leg;
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.context, leg.context.result());
            return;
        }
        leg.action.tick(leg.context);
        NavigateResult result = leg.context.result();
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.context, result);
        } else if (result != null) {
            handleProgress(controller, session, result);
        }
    }

    private void handleProgress(DroneController controller, Session session, NavigateResult result) {
        if (result.isBlocked()) {
            Leg leg = session.leg;
            session.leg = null;
            if (leg != null && leg.context.isActive()) leg.context.cancel();
            recover(controller, session, result.getError() != null
                    ? result.getError() : "Flight path is blocked");
        } else if (result.isLegDone()) {
            Leg leg = session.leg;
            session.leg = null;
            if (leg != null && leg.context.isActive()) leg.context.complete();
            if (session.digTarget != null) {
                // Reached the standoff of the obstructing voxel: clear it now.
                digPending(controller, session);
            } else {
                session.phase = Phase.PLAN;
                session.replanCooldown = 2;
            }
        }
    }

    private void handleResult(DroneController controller, Session session,
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
                    recover(controller, session, "Flight path is blocked");
                } else if (result.isLegDone()) {
                    if (session.digTarget != null) {
                        digPending(controller, session);
                    } else {
                        session.phase = Phase.PLAN;
                        session.replanCooldown = 2;
                    }
                }
        }
    }

    /**
     * Automatic recovery: navigation owns route failures instead of handing
     * them back to the model. Order of attempts:
     *
     * <ol>
     *   <li>reject the trail and replan (cheap, no world change);</li>
     *   <li>clear the obstructing voxel when digging is allowed and safe;</li>
     *   <li>try detour waypoints beside/above the goal;</li>
     *   <li>fail only when every attempt is exhausted.</li>
     * </ol>
     */
    private void recover(DroneController controller, Session session, String reason) {
        NavigationState state = state(controller);
        Drone drone = controller.drone;
        World world = drone.worldObj;

        // A pending dig whose standoff could not be reached must not be retried
        // forever: either dig from here (if it became visible) or reject it.
        if (session.digTarget != null) {
            int[] pending = session.digTarget;
            if (TunnelPlanner.inReach(drone, pending[0], pending[1], pending[2])) {
                digPending(controller, session);
                return;
            }
            session.rejectedDigs.add(pack(pending));
            session.digTarget = null;
        }

        if (session.recoveries++ >= session.recoveryAttempts) {
            fail(controller, reason + " (automatic recovery exhausted)");
            return;
        }

        if (session.usingTrail) {
            session.usingTrail = false;
            session.trailRejected = true;
            session.phase = Phase.PLAN;
            session.replanCooldown = 0;
            return;
        }

        // Rule: try not to damage anything. Fly a detour first and only tunnel
        // when no detour exists and this call allows digging.
        double[] detour = nextDetour(session, state, drone, world);
        if (detour != null) {
            session.reason = "detour-" + session.detours;
            this.logger.log("NAV", controller, "Automatic detour",
                    String.format("point=(%.1f,%.1f,%.1f) reason=%s", detour[0], detour[1], detour[2], reason));
            startLeg(controller, session, detour[0], detour[1], detour[2], "Detour");
            return;
        }

        if (session.allowDig && session.dug < session.maxDigBlocks
                && tryDig(controller, session, state, world, drone)) {
            return;
        }

        // Straight-line tunnelling fails underground: the first obstruction is
        // the floor under the drone and no standoff is reachable. Mine one
        // vertical step toward the goal instead, exactly like a player.
        if (session.allowDig && session.dug < session.maxDigBlocks
                && tryDigVertical(controller, session, state, world, drone)) {
            return;
        }

        fail(controller, reason + (session.allowDig
                ? "" : " (this goto call disabled digging)"));
    }

    /** Mines one step up or down toward a goal that is far above/below the drone. */
    private boolean tryDigVertical(DroneController controller, Session session,
                                   NavigationState state, World world, Drone drone) {
        int[] target = TunnelPlanner.nextVertical(world, drone,
                CortexDroneMod.findPlayer(controller.ownerName),
                state.goalX, state.goalY, state.goalZ);
        if (target == null) return false;
        long key = pack(target);
        if (session.rejectedDigs.contains(key)) return false;
        if (!TunnelPlanner.inReach(drone, target[0], target[1], target[2])) return false;
        session.digTarget = target;
        session.reason = "vertical";
        digPending(controller, session);
        return true;
    }

    /** Finds the obstruction toward the goal and either digs it or flies to its standoff. */
    private boolean tryDig(DroneController controller, Session session, NavigationState state,
                           World world, Drone drone) {
        EntityPlayerMP player = CortexDroneMod.findPlayer(controller.ownerName);
        int[] target = session.digTarget;
        if (target == null) {
            target = TunnelPlanner.nextObstruction(world, drone,
                    state.goalX, state.goalY, state.goalZ);
        }
        if (target == null) return false;
        if (!TunnelPlanner.safeToDig(world, player, target[0], target[1], target[2])) return false;
        long key = pack(target);
        if (session.rejectedDigs.contains(key)) return false;

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
    private void digPending(DroneController controller, Session session) {
        NavigationState state = state(controller);
        Drone drone = controller.drone;
        int[] target = session.digTarget;
        session.digTarget = null;
        if (target == null) {
            session.phase = Phase.PLAN;
            session.replanCooldown = 1;
            return;
        }
        // Journal the block before it is gone: repair_trail restores it later.
        this.manager.getDamageJournal().record(controller, drone.worldObj,
                target[0], target[1], target[2], "navigation");
        BlockResult result = this.manager.getBuildingManager().handle(
                controller, CortexDroneMod.findPlayer(controller.ownerName), "break_block",
                BlockRequest.builder()
                        .x(target[0])
                        .y(target[1])
                        .z(target[2])
                        .build());
        if (result == null || !result.isOk()) {
            session.rejectedDigs.add(pack(target));
            session.phase = Phase.PLAN;
            session.replanCooldown = 1;
            return;
        }
        session.dug++;
        this.logger.log("NAV", controller, "Auto-tunnelling",
                String.format("broke %s at (%d,%d,%d) dug=%d/%d",
                        result.getBlock(), target[0], target[1], target[2],
                        session.dug, session.maxDigBlocks));
        state.clearPath();
        state.stuckTicks = 0;
        session.phase = Phase.PLAN;
        session.replanCooldown = 1;
    }

    /**
     * Candidate detour goals: above the goal first (most maps are open above),
     * then the four lateral faces, then above the drone. Points that repeat or
     * land inside solid rock are skipped.
     */
    private double[] nextDetour(Session session, NavigationState state,
                                Drone drone, World world) {
        double[][] candidates = new double[][]{
                // Over the goal first: most maps are open above.
                {state.goalX, state.goalY + 4.0D, state.goalZ},
                {state.goalX, state.goalY + 8.0D, state.goalZ},
                {state.goalX, state.goalY + 12.0D, state.goalZ},
                {state.goalX + 6.0D, state.goalY, state.goalZ},
                {state.goalX - 6.0D, state.goalY, state.goalZ},
                {state.goalX, state.goalY, state.goalZ + 6.0D},
                {state.goalX, state.goalY, state.goalZ - 6.0D},
                // Diagonal shoulders around the goal, for tight corners.
                {state.goalX + 6.0D, state.goalY + 3.0D, state.goalZ + 6.0D},
                {state.goalX - 6.0D, state.goalY + 3.0D, state.goalZ - 6.0D},
                {state.goalX + 6.0D, state.goalY + 6.0D, state.goalZ - 6.0D},
                {state.goalX - 6.0D, state.goalY + 6.0D, state.goalZ + 6.0D},
                // Local escapes when the drone itself is boxed in.
                {drone.posX, Math.min(253.0D, drone.posY + 8.0D), drone.posZ},
                {drone.posX, Math.max(3.0D, drone.posY - 6.0D), drone.posZ},
                {drone.posX + 6.0D, drone.posY, drone.posZ},
                {drone.posX - 6.0D, drone.posY, drone.posZ},
                {drone.posX, drone.posY, drone.posZ + 6.0D},
                {drone.posX, drone.posY, drone.posZ - 6.0D},
                {drone.posX + 12.0D, drone.posY + 2.0D, drone.posZ},
                {drone.posX - 12.0D, drone.posY + 2.0D, drone.posZ},
                {drone.posX, drone.posY + 2.0D, drone.posZ + 12.0D},
                {drone.posX, drone.posY + 2.0D, drone.posZ - 12.0D}
        };
        while (session.detours < candidates.length) {
            double[] candidate = candidates[session.detours++];
            int bx = (int) Math.floor(candidate[0]);
            int by = (int) Math.floor(candidate[1]);
            int bz = (int) Math.floor(candidate[2]);
            if (by <= 2 || by >= 254) continue;
            if (!world.blockExists(bx, by, bz)) continue;
            if (!ItemClassifier.isPassableVoxel(world, bx, by, bz)) continue;
            if (!ItemClassifier.isPassableVoxel(world, bx, by + 1, bz)) continue;
            long key = pack(new int[]{bx, by, bz});
            if (session.rejectedDetours.contains(key)) continue;
            session.rejectedDetours.add(key);
            return new double[]{bx + 0.5D, by + 0.35D, bz + 0.5D};
        }
        return null;
    }

    private static long pack(int[] position) {
        return (((long) position[0] & 0x3FFFFFFL) << 38)
                | (((long) position[2] & 0x3FFFFFFL) << 12)
                | ((long) position[1] & 0xFFFL);
    }

    private void arrive(DroneController controller, Session session) {
        NavigationState state = state(controller);
        this.sessions.remove(controller);
        state.stuckTicks = 0;
        state.lastPathPartial = false;
        state.clearPath();
        controller.drone.lightColor_$eq(0x00FFAA);
        controller.drone.statusText_$eq("Arrived\n"
                + (int) Math.floor(state.goalX) + "," + (int) Math.floor(state.goalZ));
    }

    private void fail(DroneController controller, String reason) {
        this.sessions.remove(controller);
        if (controller == null) return;
        NavigationState state = state(controller);
        state.error = reason;
        state.clearPath();
        state.stuckTicks = 0;
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.logger.log("NAV", controller, "Navigation failed", reason);
    }

    private void trackStalls(DroneController controller, NavigationState state, Drone drone,
                             World world, double movedSq) {
        double distToTarget = drone.getDistance(drone.targetX(), drone.targetY(), drone.targetZ());
        if (distToTarget <= 0.70 || movedSq >= 0.0002) {
            state.stuckTicks = 0;
            return;
        }
        state.stuckTicks++;
        if (state.stuckTicks != 18 && state.stuckTicks % 30 != 0) return;
        state.lastStuckReason = FlightPathfinder.describeNearestObstruction(
                world, drone.posX, drone.posY, drone.posZ);
        // Throttle identical STUCK spam: same obstruction + same waypoint = log once per 240 ticks (12s).
        String stuckKey = controller.id + "|" + state.lastStuckReason
                + "|" + (drone.posX * 4) + "," + (drone.posY * 4) + "," + (drone.posZ * 4)
                + "->" + (int) ((double) drone.targetX() * 4) + ","
                + (int) ((double) drone.targetY() * 4) + ","
                + (int) ((double) drone.targetZ() * 4);
        if (!stuckKey.equals(state.lastStuckKey)
                || state.ticks - state.lastStuckLogTick >= 240) {
            this.logger.log("STUCK", controller,
                    String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            drone.posX, drone.posY, drone.posZ,
                            (double) drone.targetX(), (double) drone.targetY(),
                            (double) drone.targetZ()),
                    "obstruction=" + state.lastStuckReason);
            state.lastStuckKey = stuckKey;
            state.lastStuckLogTick = state.ticks;
        }
        clearLeafObstruction(controller);
    }

    private void clearLeafObstruction(DroneController controller) {
        NavigationContext context = new NavigationContext(controller, null,
                NavigationMode.FLIGHT, controller.drone.posX, controller.drone.posY,
                controller.drone.posZ, "Leaf clearance");
        new ClearLeafObstructionAction().execute(context);
    }

    private NavigationTarget resolveTarget(NavigationRequest request) {
        DroneController controller = request.getController();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        if (request.isStandoff()) {
            World world = controller.drone.worldObj;
            double[] standoff = FlightPathfinder.findBlockStandoff(world, controller.drone,
                    request.getStandoffX(), request.getStandoffY(), request.getStandoffZ());
            if (standoff == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(world,
                        request.getStandoffX(), request.getStandoffY() + 1,
                        request.getStandoffZ(), 3);
                if (open == null) return null;
                standoff = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            return new NavigationTarget(standoff[0], standoff[1], standoff[2]);
        }
        if (request.isCoordinates()) {
            return new NavigationTarget(request.getX(), request.getY(), request.getZ());
        }
        return null;
    }

    private enum Phase {
        PLAN,
        FLIGHT
    }

    private static final class Leg {
        private final Action<NavigationContext> action;
        private final NavigationContext context;

        private Leg(Action<NavigationContext> action, NavigationContext context) {
            this.action = action;
            this.context = context;
        }
    }

    private static final class Session {
        private Phase phase = Phase.PLAN;
        private Leg leg;
        private boolean trailRejected;
        private boolean usingTrail;
        private int ticks;
        private int replanCooldown;
        private String reason = "planning";

        // Automatic recovery: navigation resolves route failures itself.
        private int recoveries;
        private int detours;
        private int dug;
        private boolean allowDig = true;
        private int maxDigBlocks = 48;
        private int recoveryAttempts = 8;
        private int[] digTarget;
        private final Set<Long> rejectedDigs = new HashSet<>();
        private final Set<Long> rejectedDetours = new HashSet<>();
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
