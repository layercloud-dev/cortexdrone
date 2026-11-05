package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Thin RPC adapter for the service-layer NavigationService.
 */
@RequiredArgsConstructor
public class NavigationRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
        "move_to", "set_velocity", "follow", "stay", "stop",
        "come", "carry_player", "get_breadcrumbs",
        "retrace_breadcrumbs", "escape_cave", "navigate"
    };

    private final AIDroneManager manager;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject a = args != null ? args : new JsonObject();
        if ("navigate".equalsIgnoreCase(m)) return this.manager.getNavigationService().navigate(ctrl, a);
        return this.manager.getNavigationService().handle(ctrl, player, m, a);
    }
}
