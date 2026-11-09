package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolTimeouts;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.CombatView;
import ir.layercloud.cortexdrone.service.combat.CombatAssessment;
import ir.layercloud.cortexdrone.service.combat.CombatAssessmentService;

import java.util.Arrays;
import java.util.List;

/**
 * Read-only combat reconnaissance: target health and the exact number of
 * attacks the drone needs to finish it.
 *
 * <p>Neither tool moves or attacks; they let a script or the model decide
 * whether a kill is worth starting, which weapon will do it, and how long the
 * trade will take. Actual damage is still {@code attack_entity}, one hit per
 * call, because Minecraft damage must run on the server tick.</p>
 */
public final class CombatTools implements ToolProvider {
    private static final int MIN_SLOT = 0;
    private static final int MAX_SLOT = 7;
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("get_entity_health")
                    .description("Read one living entity's health pool: current health, max health, "
                            + "absorption, their total, armor value, Resistance potion level, remaining "
                            + "hurt-immunity ticks and the wait before the next hit lands. Also returns "
                            + "position, distance and whether it is inside the 4.5-block attack reach. "
                            + "Get the entity_id from get_entities. Read-only: it never attacks. Use it "
                            + "before combat to confirm a target is alive and reachable, and after a hit "
                            + "to see how much health remains.")
                    .parameters(ToolSchema.object()
                            .requiredInt("entity_id", "Entity id returned by get_entities.")
                            .optionalString("item", "Optional weapon filter; the read uses that item "
                                    + "instead of the auto-selected best weapon.")
                            .optionalInt("slot", "Optional drone inventory slot to evaluate as the weapon.",
                                    MIN_SLOT, MAX_SLOT, null))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.FAST_MS)
                    .build(),
            ToolSpec.builder("attack_plan")
                    .description("Calculate how to finish one entity: damage per hit with the selected "
                            + "weapon after the target's armor and Resistance, how many attacks are needed "
                            + "for current health + absorption, the wait in milliseconds between hits "
                            + "(hurt-resistance and the action cooldown), and a rough seconds estimate. "
                            + "Reports can_damage:false with a blocker when a player, invulnerability, or "
                            + "full armor/resistance makes the kill impossible. Use it instead of guessing "
                            + "hit counts; then call attack_entity exactly attacks_to_kill times, sleeping "
                            + "next_attack_in_ms between hits. Read-only.")
                    .parameters(ToolSchema.object()
                            .requiredInt("entity_id", "Entity id returned by get_entities.")
                            .optionalString("item", "Optional weapon filter; the plan uses that item "
                                    + "instead of the auto-selected best weapon.")
                            .optionalInt("slot", "Optional drone inventory slot to evaluate as the weapon.",
                                    MIN_SLOT, MAX_SLOT, null))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.FAST_MS)
                    .capabilities(ToolCapability.READ_ONLY)
                    .build()
    );

    private final CombatAssessmentService combat = new CombatAssessmentService();

    public CombatTools(AIDroneManager manager) {
        // The service is stateless; the manager argument keeps the provider
        // constructor uniform and future-proof for per-drone settings.
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("get_entity_health", getClass().getSimpleName(),
                (context, arguments) -> this.handle(context.controller(), arguments, false));
        registry.bind("attack_plan", getClass().getSimpleName(),
                (context, arguments) -> this.handle(context.controller(), arguments, true));
    }

    private JsonObject handle(DroneController controller, JsonObject args, boolean plan) {
        if (controller == null || controller.drone == null) {
            return ToolJson.error("no_drone", "No active drone is linked to this task.",
                    "Spawn or link a drone before asking about combat targets.", false);
        }
        if (!ToolArgs.has(args, "entity_id")) {
            return ToolJson.error("invalid_arguments", "entity_id is required.",
                    "Call get_entities first and pass one of its ids.", true);
        }
        int entityId = ToolArgs.integer(args, "entity_id", -1);
        String item = ToolArgs.string(args, "item", null);
        Integer slot = ToolArgs.has(args, "slot") ? ToolArgs.integer(args, "slot", -1) : null;
        CombatAssessment assessment = this.combat.assess(controller.drone, entityId, item, slot);
        return plan ? CombatView.plan(assessment) : CombatView.health(assessment);
    }
}
