package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.NavigateAction;
import ir.layercloud.cortexdrone.action.navigation.impl.StopAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.NavigationState;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
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
 * backtracking is a service query, not an action. Navigation never breaks
 * blocks: digging is composed by the model with {@code break_block}.</p>
 *
 * <p>It also owns every per-drone navigation field (goal, error, path, stall
 * counters, path-cache signature and tick clock). The controller carries none
 * of them.</p>
 */
public final class NavigationManager {
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
            return NavigationResult.failure("goto requires x/y/z or standoffOf:{x,y,z}");
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

        if (!session.trailRejected) {
            NavigationState state = state(controller);
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

        NavigationState state = state(controller);
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
            if (session.usingTrail) {
                session.usingTrail = false;
                session.trailRejected = true;
                session.phase = Phase.PLAN;
                session.replanCooldown = 0;
            } else {
                fail(controller, result.getError() != null
                        ? result.getError() : "Flight path is blocked");
            }
        } else if (result.isLegDone()) {
            Leg leg = session.leg;
            session.leg = null;
            if (leg != null && leg.context.isActive()) leg.context.complete();
            session.phase = Phase.PLAN;
            session.replanCooldown = 2;
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
                    if (session.usingTrail) {
                        session.usingTrail = false;
                        session.trailRejected = true;
                        session.phase = Phase.PLAN;
                        session.replanCooldown = 0;
                    } else {
                        fail(controller, "Flight path is blocked");
                    }
                } else if (result.isLegDone()) {
                    session.phase = Phase.PLAN;
                    session.replanCooldown = 2;
                }
        }
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
        if (!this.manager.getSessionManager().session(controller).isScriptRunning()) {
            EntityPlayerMP owner = CortexDroneMod.findPlayer(controller.ownerName);
            if (owner != null) {
                this.manager.droneSay(owner, controller, String.format(
                        "Arrived at (%.1f, %.1f, %.1f).",
                        state.goalX, state.goalY, state.goalZ));
            }
        }
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
        FlightPathfinder.clearNearbyLeafObstruction(world, controller);
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
