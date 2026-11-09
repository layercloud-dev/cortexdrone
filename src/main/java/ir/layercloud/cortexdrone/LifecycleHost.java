package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

/**
 * The manager's wiring for {@link DroneLifecycle}.
 *
 * <p>A named adapter instead of an anonymous class in the constructor, so the
 * lifecycle's external dependencies are visible and the manager reads as
 * behaviour.</p>
 */
final class LifecycleHost implements DroneLifecycle.Collaborators {
    private final AIDroneManager manager;

    LifecycleHost(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public DroneRegistry registry() {
        return this.manager.getRegistry();
    }

    @Override
    public DroneLogger logger() {
        return this.manager.getLogger();
    }

    @Override
    public ModConfig config() {
        return this.manager.getConfig();
    }

    @Override
    public void saveConfig() {
        this.manager.saveConfig();
    }

    @Override
    public boolean ensureMachineRunningState(Drone drone) {
        return this.manager.ensureMachineRunningState(drone);
    }

    @Override
    public void forgetController(DroneController controller) {
        this.manager.forgetController(controller);
    }

    @Override
    public int dropInventory(Drone drone, World world, double x, double y, double z) {
        return InventoryService.dropAllDroneItemsAt(drone, world, x, y, z);
    }
}
