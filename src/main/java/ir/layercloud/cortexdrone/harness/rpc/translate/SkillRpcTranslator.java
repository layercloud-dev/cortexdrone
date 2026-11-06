package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.SkillLibrary;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * JSON boundary for the explicit skill tools: {@code search_skills},
 * {@code list_skills} and {@code save_skill}.
 */
@RequiredArgsConstructor
public final class SkillRpcTranslator {
    private final AIDroneManager manager;

    public JsonObject handle(DroneController controller, String operation, JsonObject args) {
        if (controller == null) return RpcJson.error("No active drone");
        JsonObject response = RpcJson.ok();
        if ("save_skill".equals(operation)) {
            String name = RpcArgs.string(args, "name", "custom_skill");
            String description = RpcArgs.string(args, "description", "");
            String code = RpcArgs.string(args, "code", "");
            String tags = RpcArgs.string(args, "tags", "");
            SkillLibrary.SkillEntry entry = this.manager.getSkillLibrary()
                    .saveSkill(name, description, code, tags);
            response.addProperty("saved", entry != null);
            if (entry != null) response.addProperty("name", entry.getName());
            return response;
        }
        if ("search_skills".equals(operation) || "list_skills".equals(operation)) {
            String query = RpcArgs.string(args, "query", "");
            int limit = RpcArgs.integer(args, "limit", 10);
            List<SkillLibrary.SkillEntry> found = this.manager.getSkillLibrary()
                    .searchSkills(query, limit);
            response.addProperty("count", found.size());
            response.add("skills", this.manager.getSkillLibrary().toJsonArray(found));
            return response;
        }
        return RpcJson.error("Unsupported skill tool: " + operation);
    }
}
