package ir.layercloud.cortexdrone.harness.rpc;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * One RPC domain handler.
 *
 * <p>The handler owns its tools: {@link #toolRegistry()} is the single source of
 * truth for the names it answers and their descriptions. {@code RpcDispatcher}
 * merges every registered handler and rejects duplicate tool names, so an
 * operation can never be wired to two domains or stay orphaned.</p>
 */
public interface RpcHandler {
    ToolRegistry toolRegistry();

    JsonObject handle(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args);
}
