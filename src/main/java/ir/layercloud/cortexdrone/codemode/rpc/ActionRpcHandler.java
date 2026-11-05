package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Thin RPC adapter for the service-layer ActionService (interaction/item use/light).
 */
@RequiredArgsConstructor
public class ActionRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
        "interact_block", "use_item", "light"
    };

    private final AIDroneManager manager;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        return this.manager.getActionService().handle(ctrl, player, m, args != null ? args : new JsonObject());
    }
}
