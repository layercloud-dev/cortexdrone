package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
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
 * Goal-driven movement authority.
 *
 * <p>This is the one manager allowed to run an automatic loop. The harness only
 * sends {@code goto} or {@code stop}; the manager evaluates the recorded trail,
 * no-dig flight paths, and finally dig routes, then keeps working until the
 * drone arrives, the goal is proven unreachable, or it is explicitly stopped.</p>
 */
public final class NavigationManager {
    private static final double ARRIVAL_RADIUS = 0.85;
    private static final int STUCK_ESCALATION_TICKS = 12;
    private static final int MAX_FLIGHT_TICKS = 12000;
    private static final int MAX_DIG_ATTEMPTS = 12;

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPilot flightPilot;
    private final DigPathService digPathService;
    private final BreadcrumbService breadcrumbService;
    private final BuildingManager buildingManager;
    private final Map<DroneController, Session> sessions =
        new IdentityHashMap<DroneController, Session>();

    public NavigationManager(AIDroneManager manager, DroneLogger logger, FlightPilot flightPilot,
                             DigPathService digPathService, BreadcrumbService breadcrumbService,
                             BuildingManager buildingManager) {
        this.manager = manager;
        this.logger = logger;
        this.flightPilot = flightPilot;
        this.digPathService = digPathService;
        this.breadcrumbService = breadcrumbService;
        this.buildingManager = buildingManager;
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
        controller.navigationGeneration++;
        this.flightPilot.clear(controller);
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
        this.flightPilot.clear(controller);
        controller.stuckTicks = 0;
        controller.goalX = controller.drone.posX;
        controller.goalY = controller.drone.posY;
        controller.goalZ = controller.drone.posZ;
        FlightPathfinder.setDroneFlightTarget(controller.drone,
            controller.drone.posX, controller.drone.posY, controller.drone.posZ, 0.06f);
        controller.drone.motionX *= 0.25;
        controller.drone.motionY *= 0.25;
        controller.drone.motionZ *= 0.25;
        controller.drone.statusText_$eq("Hovering\nStopped");
        this.manager.setDroneMode(controller, Mode.STAY, reason != null ? reason : "Navigation stop");
        return NavigationResult.stopped(controller.drone.posX, controller.drone.posY,
            controller.drone.posZ);
    }

    /** Advances the active goal. Called once per tick from the runtime pipeline. */
    public void tick(DroneController controller) {
        Session session = this.sessions.get(controller);
        if (session == null || controller == null || controller.drone == null) return;
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null || world.isRemote) return;

        if (session.phase == Phase.DIG && controller.escapeResume != null) {
            return; // the building manager is executing the dig route
        }
        if (controller.mode != Mode.GOTO) {
            this.sessions.remove(controller);
            return;
        }
        if (session.phase == Phase.DIG) {
            session.phase = Phase.PLAN;
            session.replanCooldown = 0;
            session.trail = null;
            session.trailRejected = false;
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
        if (session.bestDistance - distance > 1.0) session.bestDistance = distance;

        if (session.phase == Phase.PLAN) {
            decide(controller, session);
        }
        if (session.phase == Phase.TRAIL) {
            driveTrail(controller, session);
        } else if (session.phase == Phase.FLIGHT) {
            driveFlight(controller, session);
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
        this.sessions.remove(controller);
    }

    private void decide(DroneController controller, Session session) {
        if (session.replanCooldown > 0) return;

        if (!session.trailRejected) {
            TrailPlan trail = this.breadcrumbService.planBacktrack(controller,
                session.goalX, session.goalY, session.goalZ);
            if (trail != null && trail.isUseful()) {
                session.phase = Phase.TRAIL;
                session.trail = trail.getWaypoints();
                session.trailIndex = 0;
                session.reason = trail.getReason();
                this.logger.log("NAV", controller, "Following breadcrumbs (" + trail.getReason() + ")",
                    "points=" + session.trail.size());
                return;
            }
        }

        FlightPilot.Outcome flight = this.flightPilot.flyToward(controller,
            session.goalX, session.goalY, session.goalZ, 0.1f);
        if (flight.hasPath() && !flight.isBlocked()) {
            session.phase = Phase.FLIGHT;
            session.partial = flight.isPartial();
            session.reason = session.partial ? "flight-partial" : "flight";
            return;
        }
        escalate(controller, session);
    }

    private void driveTrail(DroneController controller, Session session) {
        if (session.trail == null || session.trailIndex >= session.trail.size()) {
            session.phase = Phase.PLAN;
            session.trail = null;
            session.replanCooldown = 4;
            return;
        }
        Breadcrumb point = session.trail.get(session.trailIndex);
        double x = point.getX() + 0.5;
        double y = point.getY() + 0.65;
        double z = point.getZ() + 0.5;
        if (controller.drone.getDistance(x, y, z) <= 0.85) {
            session.trailIndex++;
            if (session.trailIndex >= session.trail.size()) {
                session.phase = Phase.PLAN;
                session.trail = null;
                session.replanCooldown = 4;
            }
            return;
        }
        if (controller.stuckTicks >= STUCK_ESCALATION_TICKS) {
            session.trailRejected = true;
            session.phase = Phase.PLAN;
            session.trail = null;
            session.replanCooldown = 0;
            return;
        }
        FlightPathfinder.setDroneFlightTarget(controller.drone, x, y, z, 0.1f);
        controller.drone.lightColor_$eq(0xFFCC00);
        controller.drone.statusText_$eq("Trail\n" + (session.trail.size() - session.trailIndex) + " pts");
    }

    private void driveFlight(DroneController controller, Session session) {
        FlightPilot.Outcome flight = this.flightPilot.flyToward(controller,
            session.goalX, session.goalY, session.goalZ, 0.1f);
        session.partial = flight.isPartial();

        if (!flight.hasPath() || flight.isBlocked()
            || controller.stuckTicks >= STUCK_ESCALATION_TICKS
            || (flight.isExhausted() && flight.isPartial())) {
            escalate(controller, session);
            return;
        }
        double distance = controller.drone.getDistance(session.goalX, session.goalY, session.goalZ);
        controller.drone.lightColor_$eq(0xFFCC00);
        controller.drone.statusText_$eq("Goto " + (int) distance + "m\n"
            + (int) session.goalX + "," + (int) session.goalY + "," + (int) session.goalZ);
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

        String kind;
        List<int[]> path;
        if (!openSky && goalOnSurface && vertical > 4.0) {
            kind = "dig-up";
            int top = world.getHeightValue(sx, sz);
            path = this.digPathService.findPath(world, sx, sy, sz, sx, Math.min(250, top + 2), sz, true);
        } else if (vertical < -6.0 && Math.abs(vertical) > Math.max(6.0, horiz * 1.1)) {
            kind = "dig-down";
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        } else {
            kind = "tunnel";
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        }

        if (path.isEmpty() && !"tunnel".equals(kind)) {
            kind = "tunnel";
            path = this.digPathService.findPath(world, sx, sy, sz, gx, gy, gz, false);
        }
        if (path.isEmpty()) {
            fail(controller, session, "No dig route to goal");
            return;
        }

        int queued = this.buildingManager.startDigRoute(controller, path,
            "Navigation " + kind + " toward (" + gx + "," + gy + "," + gz + ")");
        if (queued <= 0) {
            session.phase = Phase.PLAN;
            session.replanCooldown = 20;
            return;
        }
        session.phase = Phase.DIG;
        session.reason = kind;
        session.partial = false;
        controller.stuckTicks = 0;
        this.logger.log("NAV", controller, "Escalating to " + kind,
            "queuedDigs=" + queued + " goal=(" + gx + "," + gy + "," + gz + ")");
    }

    private void arrive(DroneController controller, Session session) {
        this.sessions.remove(controller);
        controller.stuckTicks = 0;
        this.flightPilot.clear(controller);
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
        this.flightPilot.clear(controller);
        controller.stuckTicks = 0;
        if (controller.mode == Mode.GOTO) {
            this.manager.setDroneMode(controller, Mode.STAY, reason);
        }
        if (controller.drone != null) {
            controller.drone.statusText_$eq("Route failed\n" + reason);
        }
        this.logger.log("NAV", controller, "Navigation failed", reason);
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

    private static final class Session {
        private final double goalX;
        private final double goalY;
        private final double goalZ;
        private Phase phase = Phase.PLAN;
        private List<Breadcrumb> trail;
        private int trailIndex;
        private boolean trailRejected;
        private boolean partial;
        private double bestDistance;
        private int ticks;
        private int replanCooldown;
        private int digAttempts;
        private String reason = "planning";

        private Session(double goalX, double goalY, double goalZ) {
            this.goalX = goalX;
            this.goalY = goalY;
            this.goalZ = goalZ;
            this.bestDistance = Double.MAX_VALUE;
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
