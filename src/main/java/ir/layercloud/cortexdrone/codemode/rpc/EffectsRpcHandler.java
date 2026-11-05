package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Thin RPC adapter for the service-layer EffectService.
 */
@RequiredArgsConstructor
public class EffectsRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
        "get_drone", "say", "set_status_text", "set_led", "led_effect",
        "spawn_particles", "play_sound", "play_note",
        "save_skill", "search_skills", "list_skills", "light"
    };

    private final AIDroneManager manager;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        return this.manager.getEffectService().handle(ctrl, player, m, args != null ? args : new JsonObject());
    }
}
