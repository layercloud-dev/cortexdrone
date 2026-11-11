package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.task.TaskCoordinator;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.concurrent.Callable;

/**
 * The manager's wiring for {@link InstructionRouter}.
 */
final class InstructionHost implements InstructionRouter.Host {
    private final AIDroneManager manager;

    InstructionHost(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public DroneController droneFor(String playerName) {
        return this.manager.getPlayerDrone(playerName);
    }

    @Override
    public DroneController linkNearest(EntityPlayerMP player) {
        return this.manager.linkNearestDrone(player);
    }

    @Override
    public DroneController spawn(EntityPlayerMP player, String customName) {
        return this.manager.spawnDroneForPlayer(player, customName);
    }

    @Override
    public void say(EntityPlayerMP player, DroneController controller, String message) {
        this.manager.droneSay(player, controller, message);
    }

    @Override
    public TaskCoordinator taskCoordinator() {
        return this.manager.getTaskCoordinator();
    }

    @Override
    public DroneLogger logger() {
        return this.manager.getLogger();
    }

    @Override
    public <T> T onMainThread(Callable<T> task, long timeoutMs) throws Exception {
        return this.manager.callOnMainThread(task, timeoutMs);
    }
}
