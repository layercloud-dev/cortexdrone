package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
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
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.navigation.impl.TunnelPlanner;
import li.cil.oc.common.entity.Drone;
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
 */
public final class NavigationManager implements FlightController {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final ChunkLoadingManager chunkLoader;
    private final StallReporter stallReporter;
    private final TunnelDigger digger;
    private final NavigationRecovery recovery;
    private final NavigationPresenter presenter;
    private final NavigationLegRunner legRunner;
    private final NavigationDecider decider;
    private final NavigationTicker ticker;
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
        this.digger = new TunnelDigger(manager, logger);
        this.ticker = new NavigationTicker(new TickerHost(this));
        this.decider = new NavigationDecider(new DeciderHost(this),
                this.breadcrumbService, logger);
        this.legRunner = new NavigationLegRunner(new LegRunnerHost(this),
                this.pathfinder, this.breadcrumbService);
        this.presenter = new NavigationPresenter(new PresenterHost(this));
        this.recovery = new NavigationRecovery(new RecoveryHost(this), manager, logger,
                this.stallReporter, this.digger);
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
            return NavigationResult.failure(NavigationTargetResolver.describeFailure(request));
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
        session.carryingRider = RiderGuard.isCarrying(controller.drone);
        session.speedMultiplier = NavigationSpeed.requested(request, this.manager.getConfig());
        session.maxDigBlocks = request.getMaxDigBlocks();
        session.recoveryAttempts = request.getRecoveryAttempts();
        state.speedMultiplier = NavigationSpeed.capped(session);
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
        this.ticker.tick(controller);
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

    /** Flight-speed multiplier for this drone; 1.0 is the tuned baseline. */
    public double speedMultiplier(DroneController controller) {
        return NavigationSpeed.of(this.sessions.get(controller), controller,
                this.manager.getConfig());
    }

    public NavigationStatus status(DroneController controller) {
        return NavigationStatus.of(this.sessions.get(controller), state(controller));
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
        this.decider.decide(controller, session);
    }

    void startLegFor(DroneController controller, NavigationSession session,
                     double x, double y, double z, String label) {
        startLeg(controller, session, x, y, z, label);
    }

    void failFor(DroneController controller, String reason) {
        fail(controller, reason);
    }

    void digPendingFor(DroneController controller, NavigationSession session) {
        digPending(controller, session);
    }

    void trackStall(DroneController controller, NavigationState state,
                    Drone drone, World world, double movedSq) {
        this.stallReporter.track(controller, state, drone, world, movedSq);
    }

    void arriveFor(DroneController controller, NavigationSession session) {
        arrive(controller, session);
    }

    void tickLegFor(DroneController controller, NavigationSession session) {
        tickLeg(controller, session);
    }

    void decideFor(DroneController controller, NavigationSession session) {
        decide(controller, session);
    }

    boolean startDigFor(DroneController controller, NavigationSession session, int[] target) {
        return this.recovery.startDig(controller, session, target);
    }

    void handleResultFor(DroneController controller, NavigationSession session,
                         NavigationContext context, NavigateResult result) {
        handleResult(controller, session, context, result);
    }

    void handleProgressFor(DroneController controller, NavigationSession session,
                           NavigateResult result) {
        handleProgress(controller, session, result);
    }

    void recoverFor(DroneController controller, NavigationSession session, String reason) {
        this.recovery.recover(controller, session, reason);
    }

    void cancelLegFor(NavigationSession session) {
        cancelLeg(session);
    }

    void releaseSession(DroneController controller) {
        this.sessions.remove(controller);
    }

    NavigationSession session(DroneController controller) {
        return this.sessions.get(controller);
    }

    void updateTicket(DroneController controller, NavigationState state) {
        this.chunkLoader.updateDroneTicket(controller, state);
    }

    void reportFailure(DroneController controller, NavigationState state,
                       NavigationSession session, String reason) {
        this.stallReporter.failure(controller, state, session, reason);
    }

    private void startLeg(DroneController controller, NavigationSession session,
                          double targetX, double targetY, double targetZ, String label) {
        this.legRunner.start(controller, session, targetX, targetY, targetZ, label);
    }

    private void tickLeg(DroneController controller, NavigationSession session) {
        this.legRunner.tick(controller, session);
    }

    private void handleProgress(DroneController controller, NavigationSession session,
                                NavigateResult result) {
        if (result.isBlocked()) {
            cancelLeg(session);
            session.failedRecoveries = session.lastLegMoved ? 0 : session.failedRecoveries + 1;
            this.recovery.recover(controller, session, result.getError() != null
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
                    this.recovery.recover(controller, session, "Flight path is blocked");
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

    /** Breaks the pending obstruction, then replans the route. */
    private void digPending(DroneController controller, NavigationSession session) {
        this.recovery.digPending(controller, session, state(controller));
    }

    private static void cancelLeg(NavigationSession session) {
        NavigationLegRunner.cancel(session);
    }

    private void arrive(DroneController controller, NavigationSession session) {
        this.presenter.arrive(controller, session);
    }

    private void fail(DroneController controller, String reason) {
        this.presenter.fail(controller, reason);
    }
}
