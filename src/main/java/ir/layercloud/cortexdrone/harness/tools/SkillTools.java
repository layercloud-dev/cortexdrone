package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.skill.SkillRefiner;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Skill memory tools.
 *
 * <p>{@code skill} loads/searches saved workflows; {@code skill_refine} invokes
 * the recursive compiler to turn a successful trajectory into a reusable,
 * versioned skill. Loading a skill does not execute it: the model receives the
 * code (and can run it with execute) so it can adapt parameters first.</p>
 */
public final class SkillTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("skill")
                    .description("Search, list or load reusable skills. action=search matches name, "
                            + "description, tags and trigger phrases with BM25; action=load returns the full "
                            + "code for one skill; action=list shows recently updated skills. Search before "
                            + "writing a workflow from scratch, then adapt the loaded code to the current task.")
                    .parameters(ToolSchema.object()
                            .optionalEnum("action", "Operation to perform.", "search", "search", "list", "load")
                            .optionalString("name", "Skill name (required for action=load).")
                            .optionalString("query", "Natural-language query (action=search).")
                            .optionalInt("limit", "Maximum results, 1..20.", 1, 20, 5))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("skill_refine")
                    .description("Compile a successful workflow into a reusable skill using the recursive "
                            + "skill compiler (RLM). Pass the player's task, the working JavaScript and short "
                            + "notes about parameters/blockers. The compiler writes a stable name, trigger "
                            + "phrases, retrieval tags and parameterized code, then saves a versioned skill in "
                            + "config/cortexdrone/skills.json. Use it after a multi-step task succeeds, not for "
                            + "one-off trivia.")
                    .parameters(ToolSchema.object()
                            .requiredString("task", "The original player request that the code fulfils.")
                            .requiredString("code", "The working JavaScript body (same syntax as execute).")
                            .optionalString("name", "Preferred skill name; the compiler may normalize it.")
                            .optionalString("notes", "Parameters, assumptions, blockers or variations to preserve."))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(180_000L)
                    .build()
    );

    private final SkillLibrary skills;
    private final SkillRefiner refiner;

    public SkillTools(AIDroneManager manager, SkillLibrary skills, SkillRefiner refiner) {
        this.skills = skills;
        this.refiner = refiner;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("skill", getClass().getSimpleName(),
                (context, arguments) -> skill(arguments));
        registry.bind("skill_refine", getClass().getSimpleName(),
                (context, arguments) -> refine(context.controller(), arguments));
    }

    private JsonObject skill(JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString() : "search";
        String query = args.has("query") ? args.get("query").getAsString() : "";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 5;

        if ("load".equalsIgnoreCase(action)) {
            String name = args.has("name") ? args.get("name").getAsString() : "";
            Skill skill = this.skills.get(name);
            if (skill == null) {
                JsonArray suggestions = this.skills.toJsonArray(this.skills.search(name, 3), false);
                JsonObject error = ToolJson.error("skill_not_found",
                        "No skill named '" + name + "'.", "Search with action=search first.", true);
                error.add("suggestions", suggestions);
                return error;
            }
            this.skills.recordUse(skill.getName());
            JsonObject result = ToolJson.ok();
            result.add("skill", this.skills.toJsonArray(java.util.Collections.singletonList(skill), true).get(0));
            return result;
        }

        List<Skill> found = "list".equalsIgnoreCase(action)
                ? this.skills.search("", limit) : this.skills.search(query, limit);
        JsonObject result = ToolJson.ok();
        result.addProperty("action", action);
        result.addProperty("count", found.size());
        result.add("skills", this.skills.toJsonArray(found, false));
        return result;
    }

    private JsonObject refine(ir.layercloud.cortexdrone.DroneController controller, JsonObject args) {
        String task = args.has("task") ? args.get("task").getAsString() : "";
        String code = args.has("code") ? args.get("code").getAsString() : "";
        if (task.trim().isEmpty()) {
            return ToolJson.error("invalid_arguments", "skill_refine requires 'task'.",
                    "Pass the original player request so the compiler can name and describe the skill.", true);
        }
        if (code.trim().isEmpty()) {
            return ToolJson.error("invalid_arguments", "skill_refine requires 'code'.",
                    "Pass the JavaScript body that actually completed the workflow.", true);
        }
        String name = args.has("name") ? args.get("name").getAsString() : null;
        String notes = args.has("notes") ? args.get("notes").getAsString() : "";
        Skill skill = this.refiner.refine(controller, name, task, code, notes);
        if (skill == null) {
            return ToolJson.error("refine_failed", "The skill compiler could not produce a skill.",
                    "Check that the code is non-empty and the LLM gateway is reachable, then retry.", true);
        }
        JsonObject result = ToolJson.ok();
        result.addProperty("saved", true);
        result.add("skill", this.skills.toJsonArray(java.util.Collections.singletonList(skill), true).get(0));
        return result;
    }
}
