package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.TickManager;
import ir.layercloud.cortexdrone.manager.chunk.impl.ChunkLoadingManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.session.SessionManager;

import java.util.ArrayList;

/**
 * One server tick of drone work.
 *
 * <p>Extracted from {@link AIDroneManager}: the manager forwards its tick events
 * here, and this class owns reaping dead controllers, ticking each one, and
 * deciding whether the chunk policy may evict. Keeping it separate means the
 * manager no longer mixes lifecycle events with per-drone loop mechanics.</p>
 */
final class DroneTickLoop {
    /** How often unlinked world drones are re-adopted, in ticks. */
    static final int RECONNECT_INTERVAL_TICKS = 40;
    /** How often a repeated tick failure is logged, in ticks. */
    private static final int ERROR_LOG_INTERVAL_TICKS = 40;

    /** Everything the tick loop needs from the manager. */
    interface Host {
        DroneRegistry registry();

        DroneReconnector reconnector();

        TickManager tickManager();

        TickProfiler tickProfiler();

        NavigationManager navigationManager();

        ActionRegistry actionRegistry();

        SessionManager sessionManager();

        ChunkLoadingManager chunkLoadingManager();

        DroneLogger logger();

        /** True when the harness is attached and wants its own tick. */
        void tickHarness();

        /** Ensures the drone's OpenComputers machine is running. */
        void ensureMachineRunning(DroneController controller);
    }

    private final Host host;
    private int tickCounter;

    DroneTickLoop(Host host) {
        this.host = host;
    }

    /** Start-of-tick: ensure every drone's machine is running. */
    void onTickStart() {
        this.host.tickProfiler().onTickStart();
        for (DroneController controller : this.host.registry().all()) {
            if (controller.drone != null && !controller.drone.isDead) {
                this.host.ensureMachineRunning(controller);
            }
        }
    }

    /** End-of-tick: reap, tick each drone, then let the chunk policy run. */
    void onTickEnd() {
        this.host.tickHarness();
        this.tickCounter++;
        if (this.tickCounter % RECONNECT_INTERVAL_TICKS == 0) {
            this.host.reconnector().reconnectAll();
        }

        for (DroneController controller : new ArrayList<>(this.host.registry().all())) {
            if (controller.drone == null || controller.drone.isDead) {
                this.host.registry().remove(controller);
                continue;
            }
            try {
                this.host.tickManager().tickController(controller);
            } catch (Throwable failure) {
                logTickFailure(controller, failure);
            }
        }

        this.host.tickProfiler().onTickEnd(this.tickCounter);
        this.host.chunkLoadingManager().tickPolicy(anyDroneBusy());
    }

    private void logTickFailure(DroneController controller, Throwable failure) {
        if (this.tickCounter % ERROR_LOG_INTERVAL_TICKS != 0) return;
        StackTraceElement[] trace = failure.getStackTrace();
        this.host.logger().log("ERROR", controller,
                "Exception in tickController: " + failure,
                trace.length > 0 ? trace[0].toString() : "");
    }

    /**
     * True while any drone is navigating, executing a retained action or running
     * an agent script. The chunk manager never pulls an AI-loaded chunk out from
     * under active work.
     */
    boolean anyDroneBusy() {
        for (DroneController controller : this.host.registry().all()) {
            if (controller == null || controller.drone == null || controller.drone.isDead) continue;
            if (this.host.navigationManager().isActive(controller)) return true;
            if (this.host.actionRegistry().activeContextCount(controller) > 0) return true;
            DroneSession session = this.host.sessionManager().session(controller);
            if (session != null && session.isActive()) return true;
        }
        return false;
    }

    /** Visible for diagnostics and tests. */
    int tickCount() {
        return this.tickCounter;
    }
}
