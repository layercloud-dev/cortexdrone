package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.skill.SkillRefiner;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.skill.SkillLimits;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Skill memory tools.
 *
 * <p>{@code skill} searches and loads workflows, {@code skill_run} executes one
 * with parameters in the in-process runtime, and {@code skill_refine} compiles
 * a successful trajectory into a reusable skill. Skills compose at runtime with
 * {@code skills.call(name, params)}, so a compound workflow references small
 * primitives instead of duplicating them.</p>
 */
@SuppressWarnings("unused")
public final class SkillTools implements ToolProvider {
    private static final int MIN_SEARCH_LIMIT = 1;
    private static final int MAX_SEARCH_LIMIT = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("skill")
                    .description("Search, list or load reusable skills. action=search matches name, "
                            + "description, tags and trigger phrases with BM25; action=load returns the full "
                            + "code, its parameters and the skills it requires; action=list shows recently "
                            + "updated skills. Search before writing a workflow from scratch, then run it with "
                            + "skill_run or adapt the loaded code to the current task.")
                    .parameters(ToolSchema.object()
                            .optionalEnum("action", "Operation to perform.", "search", "search", "list", "load")
                            .optionalString("name", "Skill name (required for action=load).")
                            .optionalString("query", "Natural-language query (action=search).")
                            .optionalInt("limit", "Maximum results, " + MIN_SEARCH_LIMIT + ".."
                                            + MAX_SEARCH_LIMIT + ".", MIN_SEARCH_LIMIT,
                                    MAX_SEARCH_LIMIT, DEFAULT_SEARCH_LIMIT))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .build(),
            ToolSpec.builder("skill_run")
                    .description("Run a stored skill by name with a parameter object. The skill body executes "
                            + "in the in-process JavaScript runtime with the full synchronous tool surface, so "
                            + "it can fly, mine, build, fight and call other skills. This is the fastest way to "
                            + "perform a known workflow: search with `skill`, then run it here. Parameters are "
                            + "skill-specific; action=load shows the code. Returns the skill's own result "
                            + "object plus logs. Prefer this over hand-writing a script for guard, patrol, "
                            + "farm, sort, hunt, carry or build tasks. Inside `execute` the same skill is "
                            + "callable directly as skills.<name>(params), e.g. skills.carryPlayer({...}).")
                    .parameters(ToolSchema.object()
                            .requiredString("name", "Skill name (snake_case, from `skill` search).")
                            .optionalObject("params", "Parameter object passed to the skill as `params`.")
                            .optionalInt("timeout_ms", "Wall-clock budget, "
                                            + SkillLimits.MIN_TIMEOUT_MS + ".." + SkillLimits.MAX_TIMEOUT_MS + ".",
                                    SkillLimits.MIN_TIMEOUT_MS, SkillLimits.MAX_TIMEOUT_MS,
                                    SkillLimits.DEFAULT_TIMEOUT_MS))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(SkillLimits.EXECUTE_TIMEOUT_MS)
                    .build(),
            ToolSpec.builder("skill_refine")
                    .description("Compile a successful workflow into a reusable skill using the recursive "
                            + "skill compiler (RLM). Pass the player's task, the working JavaScript and short "
                            + "notes about parameters/blockers. The compiler writes a stable name, trigger "
                            + "phrases, retrieval tags, a `requires` list and parameterized code, then saves a "
                            + "versioned skill directory in config/cortexdrone/skills/<name>/. Rejects code that "
                            + "embeds player names, coordinates, entity ids, slots or dimensions; pass those as "
                            + "params/notes instead. Use it after a multi-step task succeeds, not for one-off trivia.")
                    .parameters(ToolSchema.object()
                            .requiredString("task", "The original player request that the code fulfils.")
                            .requiredString("code", "The working JavaScript body (same syntax as execute).")
                            .optionalString("name", "Preferred skill name; the compiler may normalize it.")
                            .optionalString("notes", "Parameters, assumptions, blockers or variations to preserve."))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(SkillLimits.DEFAULT_TIMEOUT_MS)
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
        registry.bind("skill_run", getClass().getSimpleName(),
                (context, arguments) -> run(context, arguments));
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
            result.add("skill", this.skills.toJsonArray(Collections.singletonList(skill), true).get(0));
            List<String> missing = this.skills.missingRequires(skill.getName());
            result.addProperty("missing_requires_count", missing.size());
            result.add("missing_requires", new Gson().toJsonTree(missing));
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

    private JsonObject run(ToolContext context, JsonObject args) {
        if (JsEngine.isExecuting()) {
            return ToolJson.error("nested_execute",
                    "skill_run cannot start a second script from inside execute.",
                    "Call skills.call(name, params) from the running script instead.", false);
        }
        String name = args.has("name") ? args.get("name").getAsString() : "";
        Skill skill = this.skills.get(name);
        if (skill == null) {
            JsonObject error = ToolJson.error("skill_not_found",
                    "No skill named '" + name + "'.",
                    "Search with skill({action:\"search\", query:\"...\"}) and retry with the exact name.",
                    true);
            error.add("suggestions", this.skills.toJsonArray(this.skills.search(name, 3), false));
            return error;
        }
        JsEngine js = context != null ? context.js() : null;
        if (js == null) {
            return ToolJson.error("engine_unavailable",
                    "The JavaScript runtime is not initialized.", null, false);
        }
        List<String> missing = this.skills.missingRequires(skill.getName());
        if (!missing.isEmpty()) {
            JsonObject error = ToolJson.error("missing_requires",
                    "Skill '" + skill.getName() + "' needs " + missing + " which are not installed.",
                    "Load them from the bundled set or build the workflow with execute.", false);
            error.add("missing_requires", new Gson().toJsonTree(missing));
            return error;
        }
        JsonObject params = args.has("params") && args.get("params").isJsonObject()
                ? args.getAsJsonObject("params") : new JsonObject();
        long timeout = args.has("timeout_ms") ? args.get("timeout_ms").getAsLong() : SkillLimits.DEFAULT_TIMEOUT_MS;
        String code = "return skills.call(" + quote(skill.getName()) + ", "
                + params.toString() + ");";
        JsonObject result = js.execute(context, code, timeout);
        if (result != null) {
            result.addProperty("skill", skill.getName());
        }
        return result;
    }

    private static String quote(String value) {
        return new JsonPrimitive(value != null ? value : "").toString();
    }

    private JsonObject refine(DroneController controller, JsonObject args) {
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
            String hint = this.refiner.lastRejection();
            if (hint == null || hint.trim().isEmpty()) {
                hint = "Check that the code is non-empty and the LLM gateway is reachable, then retry. "
                        + "Skills must read player names, coordinates, entity ids and slots from params "
                        + "or live state, never from literals.";
            }
            return ToolJson.error("refine_failed",
                    "The skill compiler could not produce a reusable skill.", hint, true);
        }
        JsonObject result = ToolJson.ok();
        result.addProperty("saved", true);
        result.add("skill", this.skills.toJsonArray(Collections.singletonList(skill), true).get(0));
        return result;
    }
}
