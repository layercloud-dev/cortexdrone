package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import ir.layercloud.cortexdrone.harness.rpc.translate.SkillRpcTranslator;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Reusable-skill tools: {@code search_skills}, {@code list_skills} and
 * {@code save_skill}. Each is a separate tool with its own argument shape.
 */
public final class SkillRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("search_skills", "Search reusable skills by query.")
            .register("list_skills", "List reusable skills.")
            .register("save_skill", "Save a reusable skill.");

    private final SkillRpcTranslator skills;

    public SkillRpcHandler(AIDroneManager manager) {
        this.skills = new SkillRpcTranslator(manager);
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
        if (!TOOLS.contains(m)) return RpcJson.error("Unsupported skill tool: " + m);
        return this.skills.handle(ctrl, m, args);
    }
}
