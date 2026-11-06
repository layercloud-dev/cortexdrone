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
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.drone.DroneController;
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

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Goal-driven movement manager.
 *
 * <p>The harness sends only {@code goto} or {@code stop}. This manager chooses
 * a flight leg and delegates the actual movement to an action executor.
 * Managers never contain flight or waypoint code; actions do. Breadcrumb
 * backtracking is a service query, not an action. Navigation never breaks
 * blocks: digging is composed by the model with {@code break_block}.</p>
 */
public final class NavigationManager {
    private static final double ARRIVAL_RADIUS = 0.85;
    private static final int STUCK_ESCALATION_TICKS = 12;
    private static final int MAX_FLIGHT_TICKS = 12000;

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final Map<DroneController, Session> sessions =
            new IdentityHashMap<DroneController, Session>();

    public NavigationManager(AIDroneManager manager, DroneLogger logger,
                             FlightPathfinder pathfinder, BreadcrumbService breadcrumbService) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.breadcrumbService = breadcrumbService;
    }

    public NavigationResult gotoGoal(DroneController controller, EntityPlayerMP player,
                                     NavigationRequest request) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return NavigationResult.failure("No active drone");
        }
        NavigationTarget target = resolveTarget(request);
        if (target == null) {
            return NavigationResult.failure("goto requires x/y/z or standoffOf:{x,y,z}");
        }

        this.sessions.remove(controller);
        this.pathfinder.clearNavigation(controller);
        controller.stuckTicks = 0;
        controller.lastPathPartial = false;
        controller.navigationError = "";
        controller.goalX = target.getX();
        controller.goalY = target.getY();
        controller.goalZ = target.getZ();

        Session session = new Session(target.getX(), target.getY(), target.getZ());
        this.sessions.put(controller, session);
        return NavigationResult.started(target.getX(), target.getY(), target.getZ(), "planning");
    }

    public NavigationResult stop(DroneController controller, EntityPlayerMP player, String reason) {
        if (controller == null || controller.drone == null) {
            return NavigationResult.failure("No active drone");
        }
        this.sessions.remove(controller);
        this.pathfinder.clearNavigation(controller);
        controller.stuckTicks = 0;
        NavigationContext context = new NavigationContext(controller, player,
                NavigationMode.STOP, controller.drone.posX, controller.drone.posY,
                controller.drone.posZ, reason != null ? reason : "Navigation stop");
        new StopAction().execute(context);
        return NavigationResult.stopped(controller.drone.posX, controller.drone.posY,
                controller.drone.posZ);
    }

    /**
     * Called once per server tick from the runtime pipeline.
     */
    public void tick(DroneController controller) {
        Session session = this.sessions.get(controller);
        if (session == null || controller == null || controller.drone == null) return;
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null || world.isRemote) return;

        if (session.leg != null) {
            tickLeg(controller, session);
            return;
        }
        session.ticks++;
        if (session.replanCooldown > 0) session.replanCooldown--;
        if (session.ticks > MAX_FLIGHT_TICKS) {
            fail(controller, session, "Navigation timed out");
            return;
        }

        double distance = drone.getDistance(session.goalX, session.goalY, session.goalZ);
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
    public NavigateResult flyToward(DroneController controller, double x, double y, double z,
                                    String label) {
        if (controller == null || controller.drone == null) {
            return NavigateResult.failure("No active drone");
        }
        NavigationContext context = new NavigationContext(controller, null,
                NavigationMode.FLIGHT, x, y, z, label);
        NavigateAction action = new NavigateAction(this.pathfinder, this.breadcrumbService);
        try {
            action.tick(context);
            return context.result() != null
                    ? context.result() : NavigateResult.failure("No flight result");
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
        return new Status(true, session.phase.name().toLowerCase(), session.reason,
                session.goalX, session.goalY, session.goalZ);
    }

    public void clear(DroneController controller) {
        Session session = this.sessions.get(controller);
        if (session != null && session.leg != null && session.leg.context.isActive()) {
            session.leg.action.cancel(session.leg.context);
        }
        this.sessions.remove(controller);
    }

    private void decide(DroneController controller, Session session) {
        if (session.replanCooldown > 0) return;

        if (!session.trailRejected) {
            TrailPlan trail = this.breadcrumbService.planBacktrack(controller,
                    session.goalX, session.goalY, session.goalZ);
            if (trail != null && trail.isUseful()) {
                Breadcrumb endpoint = trail.getEndpoint();
                session.usingTrail = true;
                session.reason = "trail-" + trail.getReason();
                startLeg(controller, session, NavigationMode.FLIGHT,
                        endpoint.getX() + 0.5, endpoint.getY() + 0.65, endpoint.getZ() + 0.5,
                        "Trail endpoint (" + trail.getReason() + ")");
                this.logger.log("NAV", controller, "Flying to trail endpoint (" + trail.getReason() + ")",
                        "point=(" + endpoint.getX() + "," + endpoint.getY() + "," + endpoint.getZ() + ")");
                return;
            }
        }

        session.usingTrail = false;
        session.reason = "flight";
        startLeg(controller, session, NavigationMode.FLIGHT,
                session.goalX, session.goalY, session.goalZ, "Flight");
    }

    private void startLeg(DroneController controller, Session session, NavigationMode mode,
                          double targetX, double targetY, double targetZ, String label) {
        session.phase = Phase.FLIGHT;
        NavigationContext context = new NavigationContext(controller, null, mode,
                targetX, targetY, targetZ, label);
        Action<NavigationContext> action = actionFor(mode);
        action.execute(context);
        if (context.isActive()) {
            context.retain();
            session.leg = new Leg(action, context);
        } else {
            session.leg = null;
            handleResult(controller, session, action, context, context.result());
        }
    }

    private void tickLeg(DroneController controller, Session session) {
        Leg leg = session.leg;
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.action, leg.context, leg.context.result());
            return;
        }
        leg.action.tick(leg.context);
        NavigateResult result = leg.context.result();
        if (!leg.context.isActive()) {
            session.leg = null;
            handleResult(controller, session, leg.action, leg.context, result);
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
                fail(controller, session, result.getError() != null
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

    private void handleResult(DroneController controller, Session session, Action<NavigationContext> action,
                              NavigationContext context, NavigateResult result) {
        if (result == null) {
            fail(controller, session, "Navigation action produced no result");
            return;
        }
        if (result.getError() != null) {
            fail(controller, session, result.getError());
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
                        fail(controller, session, "Flight path is blocked");
                    }
                } else if (result.isLegDone()) {
                    session.phase = Phase.PLAN;
                    session.replanCooldown = 2;
                }
        }
    }

    private void arrive(DroneController controller, Session session) {
        this.sessions.remove(controller);
        controller.stuckTicks = 0;
        this.pathfinder.clearNavigation(controller);
        controller.drone.lightColor_$eq(0x00FFAA);
        controller.drone.statusText_$eq("Arrived\n"
                + (int) Math.floor(session.goalX) + "," + (int) Math.floor(session.goalZ));
        if (!controller.isScriptRunning()) {
            EntityPlayerMP owner = CortexDroneMod.findPlayer(controller.ownerName);
            if (owner != null) {
                this.manager.droneSay(owner, controller, String.format(
                        "Arrived at (%.1f, %.1f, %.1f).", session.goalX, session.goalY, session.goalZ));
            }
        }
    }

    private void fail(DroneController controller, Session session, String reason) {
        this.sessions.remove(controller);
        if (controller == null) return;
        controller.navigationError = reason;
        this.pathfinder.clearNavigation(controller);
        controller.stuckTicks = 0;
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.logger.log("NAV", controller, "Navigation failed", reason);
    }

    private Action<NavigationContext> actionFor(NavigationMode mode) {
        switch (mode) {
            case STOP:
                return new StopAction();
            case FLIGHT:
            default:
                return new NavigateAction(this.pathfinder, this.breadcrumbService);
        }
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
        private final double goalX;
        private final double goalY;
        private final double goalZ;
        private Phase phase = Phase.PLAN;
        private Leg leg;
        private boolean trailRejected;
        private boolean usingTrail;
        private int ticks;
        private int replanCooldown;
        private String reason = "planning";

        private Session(double goalX, double goalY, double goalZ) {
            this.goalX = goalX;
            this.goalY = goalY;
            this.goalZ = goalZ;
        }
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
