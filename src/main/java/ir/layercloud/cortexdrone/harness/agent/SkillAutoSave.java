package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillCodeGuard;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.util.Collections;
import java.util.List;

/**
 * Safety net for the "learn after success" rule.
 *
 * <p>When a successful script performed real work but the model skipped
 * {@code skill_refine}, the exact code is saved as a skill anyway. The model
 * still owns the good description; this only guarantees that a working workflow
 * is not thrown away. Scripts that embed instance-specific literals are skipped:
 * a hardcoded recording is not a reusable skill.</p>
 *
 * <p>Extracted from {@code AgentService} so the loop stays under the modular
 * line ceiling and the save policy has one owner.</p>
 */
final class SkillAutoSave {
    /**
     * Shortest script worth keeping; shorter ones are single tool calls.
     */
    private static final int MIN_CODE_CHARS = 200;

    private final ToolRegistry registry;
    private final SkillLibrary skills;
    private final DroneLogger logger;

    SkillAutoSave(ToolRegistry registry, SkillLibrary skills, DroneLogger logger) {
        this.registry = registry;
        this.skills = skills;
        this.logger = logger;
    }

    void maybeSave(DroneController controller, String prompt, String code, boolean refined) {
        if (refined || code == null || code.trim().length() < MIN_CODE_CHARS) return;
        if (!performsWorldAction(code)) return;
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(code);
        if (!violations.isEmpty()) {
            this.logger.log("SKILL", controller,
                    "Auto-save skipped: script contains instance-specific literals",
                    SkillCodeGuard.describe(violations));
            return;
        }
        String name = ChatLines.skillName(prompt);
        Skill skill = this.skills.save(name, ChatLines.limit(prompt, 220), "auto",
                Collections.singletonList(ChatLines.limit(prompt, 120)), code, "auto");
        if (skill == null) return;
        this.logger.log("SKILL", controller, "Auto-saved skill '" + skill.getName() + "' v"
                        + skill.getVersion(),
                "model did not call skill_refine; script kept verbatim");
    }

    /**
     * True when the script calls a tool that changes the world or an inventory.
     *
     * <p>Derived from the tool registry's declared capabilities, so a newly
     * registered mutating tool is recognised without editing a name list here.
     * The JS method name is the camelCase form of the tool name.</p>
     */
    private boolean performsWorldAction(String code) {
        if (code == null) return false;
        for (ToolSpec spec : this.registry.specs()) {
            if (!spec.mutatesAnything()) continue;
            String jsName = JsEngine.camelCase(spec.getName());
            if (!jsName.isEmpty() && code.contains(jsName)) return true;
        }
        return false;
    }
}
