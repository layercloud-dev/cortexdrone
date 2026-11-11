package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;

/**
 * The manager's wiring for {@link DroneReconnector}.
 *
 * <p>A named adapter instead of a nine-method anonymous class inside the
 * constructor. Because it is a top-level class, the reconnector's dependencies
 * are visible at a glance and the manager's constructor stays readable.</p>
 */
final class ReconnectHost implements DroneReconnector.Host {
    private final AIDroneManager manager;

    ReconnectHost(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public DroneController findControllerByEntity(Drone drone) {
        return this.manager.findControllerByEntity(drone);
    }

    @Override
    public DroneController getPlayerDrone(String playerName) {
        return this.manager.getPlayerDrone(playerName);
    }

    @Override
    public DroneController registerDroneController(Drone drone, String name, String ownerName) {
        return this.manager.registerDroneController(drone, name, ownerName);
    }

    @Override
    public boolean wasDespawned(Drone drone) {
        return this.manager.wasDespawned(drone);
    }

    @Override
    public boolean ensureMachineRunningState(Drone drone) {
        return this.manager.ensureMachineRunningState(drone);
    }

    @Override
    public ModConfig config() {
        return this.manager.getConfig();
    }

    @Override
    public DroneLogger logger() {
        return this.manager.getLogger();
    }

    @Override
    public VisualsManager visualsManager() {
        return this.manager.getVisualsManager();
    }

    @Override
    public void restoreVisuals(DroneController controller, boolean lightAura, Integer ledColor) {
        VisualsState visuals = visualsManager().state(controller);
        visuals.lightAura = lightAura;
        if (ledColor != null && controller.drone != null) {
            controller.drone.lightColor_$eq(ledColor & 0xFFFFFF);
        }
        visualsManager().restore(controller);
    }

    @Override
    public int mergeInventory(Drone from, Drone to) {
        return InventoryService.withdrawFromInventory(
                from.mainInventory(), to.mainInventory(), "all", InventoryLimits.DEFAULT_TRANSFER_COUNT);
    }
}
