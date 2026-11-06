package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.action.navigation.impl.DigRouteAction;
import ir.layercloud.cortexdrone.action.navigation.impl.HoldAction;
import ir.layercloud.cortexdrone.action.navigation.impl.NavigateAction;
import ir.layercloud.cortexdrone.action.navigation.impl.StopAction;
import ir.layercloud.cortexdrone.action.navigation.impl.TrailAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.navigation.impl.NavigationContext;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.DigPathService;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.TrailPlan;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Goal-driven movement manager.
 *
 * <p>The harness sends only {@code goto} or {@code stop}. This manager chooses
 * a leg (trail, flight, tunnel, dig-up, dig-down) and delegates the actual
 * movement to an action executor. Managers never contain flight or waypoint
 * code; actions do.</p>
 */
public final class NavigationManager {
    private static final double ARRIVAL_RADIUS = 0.85;
    private static final int STUCK_ESCALATION_TICKS = 12;
    private static final int MAX_FLIGHT_TICKS = 12000;
    private static final int MAX_DIG_ATTEMPTS = 12;

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final DigPathService digPathService = new DigPathService();
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
        controller.navigationGeneration++;
        controller.stuckTicks = 0;
        controller.lastPathPartial = false;
        controller.navigationError = "";
        controller.goalX = target.getX();
        controller.goalY = target.getY();
        controller.goalZ = target.getZ();

        Session session = new Session(target.getX(), target.getY(), target.getZ());
        this.sessions.put(controller, session);
        this.manager.setDroneMode(controller, Mode.GOTO,
            String.format("goto (%.1f,%.1f,%.1f)", target.getX(), target.getY(), target.getZ()));
        return NavigationResult.started(target.getX(), target.getY(), target.getZ(), "planning");
    }

    public NavigationResult stop(DroneController controller, EntityPlayerMP player, String reason) {
        if (controller == null || controller.drone == null) {
            return NavigationResult.failure("No active drone");
        }
        this.sessions.remove(controller);
        controller.navigationGeneration++;
        this.pathfinder.clearNavigation(controller);
        controller.stuckTicks = 0;
        NavigationContext context = new NavigationContext(controller, player,
            NavigationMode.STOP, controller.drone.posX, controller.drone.posY,
            controller.drone.posZ, null, null, reason != null ? reason : "Navigation stop");
        new StopAction(this.manager).execute(context);
        return NavigationResult.stopped(controller.drone.posX, controller.drone.posY,
            controller.drone.posZ);
    }

    /** Called once per server tick from the runtime pipeline. */
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
        if (session.phase == Phase.DIG) {
            if (controller.escapeResume != null || controller.mode == Mode.MINE) return;
            if (controller.mode != Mode.GOTO) {
                fail(controller, session, "Navigation interrupted by " + controller.mode.name());
                return;
            }
            session.phase = Phase.PLAN;
            session.replanCooldown = 0;
            session.trailRejected = false;
        }
        if (controller.mode != Mode.GOTO) {
            this.sessions.remove(controller);
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
            NavigationMode.FLIGHT, x, y, z, null, null, label);
        NavigateAction action = new NavigateAction(this.pathfinder, this.breadcrumbService);
        try {
            action.tick(context);
            return context.result() != null
                ? context.result() : NavigateResult.failure("No flight result");
        } finally {
            context.complete();
        }
    }

    /** Holds the current position. All motion is applied by {@link HoldAction}. */
    public NavigateResult hold(DroneController controller, String label) {
        if (controller == null || controller.drone == null) {
            return NavigateResult.failure("No active drone");
        }
        NavigationContext context = new NavigationContext(controller, null,
            NavigationMode.HOLD, controller.drone.posX, controller.drone.posY,
            controller.drone.posZ, null, null, label);
        new HoldAction().execute(context);
        return context.result() != null ? context.result() : NavigateResult.done();
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
                session.reason = trail.getReason();
                startLeg(controller, session, NavigationMode.TRAIL,
                    trail.getWaypoints(), null, "Breadcrumbs (" + trail.getReason() + ")");
                this.logger.log("NAV", controller, "Following breadcrumbs (" + trail.getReason() + ")",
                    "points=" + trail.getWaypoints().size());
                return;
            }
        }

        session.reason = "flight";
        startLeg(controller, session, NavigationMode.FLIGHT, null, null, "Flight");
    }

    private void startLeg(DroneController controller, Session session, NavigationMode mode,
                          List<Breadcrumb> trail, List<int[]> digPath, String label) {
        session.phase = phaseFor(mode);
        NavigationContext context = new NavigationContext(controller, null, mode,
            session.goalX, session.goalY, session.goalZ, trail, digPath, label);
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

    private static Phase phaseFor(NavigationMode mode) {
        if (mode == NavigationMode.TRAIL) return Phase.TRAIL;
        if (mode == NavigationMode.FLIGHT) return Phase.FLIGHT;
        return Phase.DIG;
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
        if (result.isNeedsDig() || result.isBlocked()) {
            Leg leg = session.leg;
            session.leg = null;
            if (leg != null && leg.context.isActive()) leg.context.cancel();
            escalate(controller, session);
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
            case DIG_UP:
            case DIG_DOWN:
            case TUNNEL:
                session.phase = Phase.DIG;
                session.reason = context.mode().name().toLowerCase();
                controller.stuckTicks = 0;
                return;
            case TRAIL:
                if (result.isNeedsDig() || result.isBlocked()) {
                    session.trailRejected = true;
                    escalate(controller, session);
                } else {
                    session.phase = Phase.PLAN;
                    session.replanCooldown = 4;
                }
                return;
            case FLIGHT:
            default:
                if (result.isNeedsDig() || result.isBlocked()) {
                    escalate(controller, session);
                } else if (result.isLegDone()) {
                    session.phase = Phase.PLAN;
                    session.replanCooldown = 2;
                }
                return;
        }
    }

    private void escalate(DroneController controller, Session session) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            fail(controller, session, "No active drone");
            return;
        }
        if (session.digAttempts >= MAX_DIG_ATTEMPTS) {
            fail(controller, session, "No reachable route after " + session.digAttempts + " dig attempts");
            return;
        }
        session.digAttempts++;

        Drone drone = controller.drone;
        World world = drone.worldObj;
        int sx = MathHelper.floor_double(drone.posX);
        int sy = MathHelper.floor_double(drone.posY);
        int sz = MathHelper.floor_double(drone.posZ);
        int gx = MathHelper.floor_double(session.goalX);
        int gy = MathHelper.floor_double(session.goalY);
        int gz = MathHelper.floor_double(session.goalZ);

        boolean openSky = world.canBlockSeeTheSky(sx, sy, sz);
        int targetTop = world.getHeightValue(gx, gz);
        boolean goalOnSurface = session.goalY >= targetTop - 3;
        double horiz = Math.hypot(session.goalX - drone.posX, session.goalZ - drone.posZ);
        double vertical = session.goalY - drone.posY;

        NavigationMode mode;
        List<int[]> path;
        if (!openSky && goalOnSurface && vertical > 4.0) {
            mode = NavigationMode.DIG_UP;
            int top = world.getHeightValue(sx, sz);
            path = this.digPathService.findPath(world, sx, sy, sz, sx, Math.min(250, top + 2), sz, true);
        } else if (vertical < -6.0 && Math.abs(vertical) > Math.max(6.0, horiz * 1.1)) {
            mode = NavigationMode.DIG_DOWN;
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        } else {
            mode = NavigationMode.TUNNEL;
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        }
        if (path.isEmpty() && mode != NavigationMode.TUNNEL) {
            mode = NavigationMode.TUNNEL;
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        }
        if (path.isEmpty()) {
            fail(controller, session, "No dig route to goal");
            return;
        }

        startLeg(controller, session, mode, null, path,
            "Navigation " + mode.name().toLowerCase() + " toward (" + gx + "," + gy + "," + gz + ")");
        session.reason = mode.name().toLowerCase();
        controller.stuckTicks = 0;
        this.logger.log("NAV", controller, "Escalating to " + mode.name().toLowerCase(),
            "steps=" + path.size() + " goal=(" + gx + "," + gy + "," + gz + ")");
    }

    private void arrive(DroneController controller, Session session) {
        this.sessions.remove(controller);
        controller.stuckTicks = 0;
        this.pathfinder.clearNavigation(controller);
        this.manager.setDroneMode(controller, Mode.STAY, "Arrived at navigation goal");
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
        if (controller.mode == Mode.GOTO) {
            this.manager.setDroneMode(controller, Mode.STAY, reason);
        }
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.logger.log("NAV", controller, "Navigation failed", reason);
    }

    private Action<NavigationContext> actionFor(NavigationMode mode) {
        switch (mode) {
            case TRAIL:
                return new TrailAction();
            case TUNNEL:
            case DIG_UP:
            case DIG_DOWN:
                return new DigRouteAction(this.manager.getBuildingManager());
            case STOP:
                return new StopAction(this.manager);
            case HOLD:
                return new HoldAction();
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
            double[] standoff = FlightPathfinder.findMiningStandoff(world, controller.drone,
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
        TRAIL,
        FLIGHT,
        DIG
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
        private int ticks;
        private int replanCooldown;
        private int digAttempts;
        private String reason = "planning";

        private Session(double goalX, double goalY, double goalZ) {
            this.goalX = goalX;
            this.goalY = goalY;
            this.goalZ = goalZ;
        }
    }

    public static final class Status {
        private final boolean active;
        private final String phase;
        private final String reason;
        private final double goalX;
        private final double goalY;
        private final double goalZ;

        private Status(boolean active, String phase, String reason,
                       double goalX, double goalY, double goalZ) {
            this.active = active;
            this.phase = phase;
            this.reason = reason;
            this.goalX = goalX;
            this.goalY = goalY;
            this.goalZ = goalZ;
        }

        public static Status idle() {
            return new Status(false, "idle", "", 0, 0, 0);
        }

        public boolean isActive() { return this.active; }
        public String getPhase() { return this.phase; }
        public String getReason() { return this.reason; }
        public double getGoalX() { return this.goalX; }
        public double getGoalY() { return this.goalY; }
        public double getGoalZ() { return this.goalZ; }
    }
}
