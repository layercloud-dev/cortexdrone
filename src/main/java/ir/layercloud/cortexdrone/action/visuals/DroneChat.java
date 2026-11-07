package ir.layercloud.cortexdrone.action.visuals;

import ir.layercloud.cortexdrone.DroneController;
import net.minecraft.command.ICommandSender;

/**
 * Narrow chat output port for actions that speak as the drone. Implemented by
 * the root manager; consumed by actions so the action layer never imports it.
 */
public interface DroneChat {
    void say(ICommandSender recipient, DroneController controller, String message);
}
