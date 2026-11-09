package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.session.SessionManager;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.TickManager;
import ir.layercloud.cortexdrone.manager.chunk.impl.ChunkLoadingManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;

/** The manager's wiring for {@link DroneTickLoop}. */
final class TickLoopHost implements DroneTickLoop.Host {
    private final AIDroneManager manager;

    TickLoopHost(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public DroneRegistry registry() {
        return this.manager.getRegistry();
    }

    @Override
    public DroneReconnector reconnector() {
        return this.manager.getReconnector();
    }

    @Override
    public TickManager tickManager() {
        return this.manager.getTickManager();
    }

    @Override
    public TickProfiler tickProfiler() {
        return this.manager.getTickProfiler();
    }

    @Override
    public NavigationManager navigationManager() {
        return this.manager.getNavigationManager();
    }

    @Override
    public ActionRegistry actionRegistry() {
        return this.manager.getActionRegistry();
    }

    @Override
    public SessionManager sessionManager() {
        return this.manager.getSessionManager();
    }

    @Override
    public ChunkLoadingManager chunkLoadingManager() {
        return this.manager.getChunkLoadingManager();
    }

    @Override
    public DroneLogger logger() {
        return this.manager.getLogger();
    }

    @Override
    public void tickHarness() {
        if (this.manager.getHarness() != null) this.manager.getHarness().onServerTickEnd();
    }

    @Override
    public void ensureMachineRunning(DroneController controller) {
        if (controller.drone != null) this.manager.ensureMachineRunningState(controller.drone);
    }
}
