package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import ir.layercloud.cortexdrone.harness.rpc.translate.VisualsRpcTranslator;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Visual/audio/chat tools. Each tool maps to exactly one visuals operation, so
 * the model never needs a {@code kind} discriminator.
 */
public final class VisualsRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("set_led", "Set the drone LED to one static color.")
            .register("set_light", "Enable, disable or configure the illumination aura.")
            .register("spawn_particles", "Spawn one particle burst.")
            .register("play_sound", "Play one sound effect.")
            .register("play_note", "Play one note by semitone.")
            .register("say", "Send one chat message as the drone.")
            .register("set_status_text", "Set the drone status text.");

    private final VisualsRpcTranslator visuals;

    public VisualsRpcHandler(AIDroneManager manager) {
        this.visuals = new VisualsRpcTranslator(manager.getVisualsManager());
    }

    /** The tools this handler owns, available without a live manager. */
    public static ToolRegistry tools() {
        return TOOLS;
    }

    @Override
    public ToolRegistry toolRegistry() {
        return TOOLS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (!TOOLS.contains(m)) return RpcJson.error("Unsupported visuals tool: " + m);
        return this.visuals.handle(ctrl, player, m, args);
    }
}
