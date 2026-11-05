package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Common contract for categorized action services.
 */
public interface Action {
    /** Human-readable category, e.g. block, interact, combat, effect, movement. */
    String category();

    /** Canonical operation names handled by this action. */
    String[] operations();

    boolean supports(String operation);

    JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args);
}
