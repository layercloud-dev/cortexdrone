package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.agent.AgentResources;
import ir.layercloud.cortexdrone.harness.agent.LlmClient;
import ir.layercloud.cortexdrone.harness.agent.LlmResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive skill compiler ("RLM" step).
 *
 * <p>After a successful workflow the model can call {@code skill_refine}. That
 * tool sends the task, the working code and the model's notes to the same LLM
 * with a dedicated compiler prompt, and asks it to return a normalized skill
 * (stable name, trigger phrases, retrieval tags, parameterized code). If the
 * compiler call fails or returns unusable JSON, the working code is saved
 * verbatim under a derived name so the knowledge is never lost.</p>
 */
public final class SkillRefiner {
    private static final String COMPILER_PROMPT =
            AgentResources.load("/prompts/skill-refine.md");

    private final LlmClient llm;
    private final SkillLibrary library;
    private final DroneLogger logger;
    private volatile String lastRejection;

    public SkillRefiner(LlmClient llm, SkillLibrary library, DroneLogger logger) {
        this.llm = llm;
        this.library = library;
        this.logger = logger;
    }

    /** Why the last refine attempt was refused, or null when it succeeded. */
    public String lastRejection() {
        return this.lastRejection;
    }

    public Skill refine(DroneController controller, String requestedName, String task,
                        String code, String notes) {
        if (code == null || code.trim().isEmpty()) return null;
        this.lastRejection = null;
        List<SkillCodeGuard.Violation> sourceViolations = SkillCodeGuard.inspect(code);
        JsonObject compiled = compile(controller, requestedName, task, code, notes,
                sourceViolations, java.util.Collections.<SkillCodeGuard.Violation>emptyList());
        if (compiled == null) return fallback(requestedName, task, code);

        List<SkillCodeGuard.Violation> violations =
                SkillCodeGuard.inspect(codeOf(compiled, code));
        if (!violations.isEmpty()) {
            this.logger.log("SKILL", controller,
                    "Skill compiler copied instance-specific literals; retrying",
                    SkillCodeGuard.describe(violations));
            JsonObject retry = compile(controller, requestedName, task, code, notes,
                    sourceViolations, violations);
            if (retry != null) {
                compiled = retry;
                violations = SkillCodeGuard.inspect(codeOf(compiled, code));
            }
        }
        if (!violations.isEmpty()) {
            this.lastRejection = "Rejected a skill with instance-specific literals: "
                    + SkillCodeGuard.describe(violations)
                    + ". Read these values from params or live player/drone state instead.";
            this.logger.log("SKILL", controller, "Refused to save hardcoded skill",
                    SkillCodeGuard.describe(violations));
            return null;
        }

        Skill skill = save(compiled, requestedName, task, code);
        if (skill != null) return skill;
        return fallback(requestedName, task, code);
    }

    private JsonObject compile(DroneController controller, String requestedName,
                               String task, String code, String notes,
                               List<SkillCodeGuard.Violation> sourceViolations,
                               List<SkillCodeGuard.Violation> previousViolations) {
        if (COMPILER_PROMPT == null || COMPILER_PROMPT.isEmpty()) return null;
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system", COMPILER_PROMPT));
        StringBuilder user = new StringBuilder();
        user.append("Requested name hint: ").append(requestedName != null ? requestedName : "(none)").append("\n\n");
        user.append("Player task:\n").append(limit(task, 2_000)).append("\n\n");
        user.append("Working JavaScript:\n```js\n").append(limit(code, 8_000)).append("\n```\n\n");
        if (notes != null && !notes.trim().isEmpty()) {
            user.append("Agent notes / blockers / parameters:\n").append(limit(notes, 2_000)).append("\n\n");
        }
        if (!sourceViolations.isEmpty()) {
            user.append("Instance-specific literals found in the working JavaScript. ");
            user.append("Do not copy them into the skill:\n");
            appendViolations(user, sourceViolations);
            user.append('\n');
        }
        if (!previousViolations.isEmpty()) {
            user.append("Your previous JSON still contained instance-specific literals:\n");
            appendViolations(user, previousViolations);
            user.append("Rewrite the code so every one is read from params (or ctx/drone/player "
                    + "state) with a safe default. The skill must work for any player, position "
                    + "and run.\n\n");
        }
        user.append("Compile this into the JSON skill object described by the system prompt.");
        messages.add(message("user", user.toString()));
        try {
            LlmResponse response = this.llm.chat(controller, messages, null, true);
            return parseJson(response.content());
        } catch (Throwable t) {
            this.logger.log("SKILL", controller, "Skill compiler call failed; using fallback",
                    t.toString());
            return null;
        }
    }

    private Skill save(JsonObject compiled, String requestedName, String task, String code) {
        String name = text(compiled, "name");
        if (name == null || name.trim().isEmpty()) name = requestedName;
        if (name == null || name.trim().isEmpty()) name = deriveName(task);
        String description = text(compiled, "description");
        if (description == null || description.trim().isEmpty()) description = limit(task, 220);
        String tags = text(compiled, "tags");
        List<String> triggers = triggers(compiled.get("triggers"));
        List<String> requires = triggers(compiled.get("requires"));
        String docs = text(compiled, "docs");
        String compiledCode = codeOf(compiled, code);
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(compiledCode);
        if (!violations.isEmpty()) {
            this.lastRejection = "Refused to save a skill with instance-specific literals: "
                    + SkillCodeGuard.describe(violations);
            this.logger.log("SKILL", null, "Refused to save hardcoded compiled code",
                    SkillCodeGuard.describe(violations));
            return null;
        }
        Skill skill = this.library.save(name, description, tags, triggers, requires,
                docs, compiledCode, "refined");
        if (skill != null) {
            this.logger.log("SKILL", null, "Refined skill '" + skill.getName() + "' v"
                    + skill.getVersion(), "triggers=" + triggers);
        }
        return skill;
    }

    private Skill fallback(String requestedName, String task, String code) {
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(code);
        if (!violations.isEmpty()) {
            this.lastRejection = "The working code contains instance-specific literals ("
                    + SkillCodeGuard.describe(violations)
                    + "). Parameterize them before saving the skill.";
            this.logger.log("SKILL", null, "Refused to save hardcoded fallback skill",
                    SkillCodeGuard.describe(violations));
            return null;
        }
        String name = requestedName != null && !requestedName.trim().isEmpty()
                ? requestedName : deriveName(task);
        List<String> triggers = new ArrayList<>();
        if (task != null && !task.trim().isEmpty()) {
            triggers.add(limit(task.trim(), 80));
        }
        Skill skill = this.library.save(name, limit(task, 220), "", triggers, null, code, "refined");
        if (skill != null) {
            this.logger.log("SKILL", null, "Saved fallback skill '" + skill.getName() + "' v"
                    + skill.getVersion(), "compiler unavailable or invalid JSON");
        }
        return skill;
    }

    private static String codeOf(JsonObject compiled, String fallback) {
        String compiledCode = text(compiled, "code");
        return compiledCode == null || compiledCode.trim().isEmpty() ? fallback : compiledCode;
    }

    private static void appendViolations(StringBuilder out,
                                         List<SkillCodeGuard.Violation> violations) {
        int limit = Math.min(violations.size(), 8);
        for (int i = 0; i < limit; i++) {
            out.append("- ").append(violations.get(i).feedback()).append('\n');
        }
        if (violations.size() > limit) {
            out.append("- ... ").append(violations.size() - limit).append(" more\n");
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject parseJson(String content) {
        if (content == null) return null;
        int open = content.indexOf('{');
        int close = content.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        try {
            JsonElement element = new JsonParser().parse(content.substring(open, close + 1));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static List<String> triggers(JsonElement element) {
        List<String> triggers = new ArrayList<>();
        if (element == null || element.isJsonNull()) return triggers;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) triggers.add(item.getAsString());
            }
        } else if (element.isJsonPrimitive()) {
            String raw = element.getAsString();
            for (String part : raw.split("[,\\n]")) {
                if (!part.trim().isEmpty()) triggers.add(part.trim());
            }
        }
        return triggers;
    }

    private static String deriveName(String task) {
        if (task == null || task.trim().isEmpty()) return "learned_skill";
        String slug = SkillLibrary.normalize(task);
        if (slug.length() > 36) slug = slug.substring(0, 36);
        return "learned_" + slug;
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n...[truncated]";
    }
}
