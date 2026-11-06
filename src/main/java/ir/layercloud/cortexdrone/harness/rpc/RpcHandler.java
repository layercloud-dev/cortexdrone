package ir.layercloud.cortexdrone.harness.rpc;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Modular RPC domain handler interface registered with RpcDispatcher.
 */
public interface RpcHandler {
    String[] getSupportedMethods();

    JsonObject handle(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args);
}
