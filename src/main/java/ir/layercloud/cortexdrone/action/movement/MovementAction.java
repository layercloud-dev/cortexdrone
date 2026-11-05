package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Typed movement executor contract. Movement actions calculate and start a
 * route; they do not build RPC JSON.
 */
public interface MovementAction {
    String operation();

    MovementResult execute(MovementRequest request);
}
