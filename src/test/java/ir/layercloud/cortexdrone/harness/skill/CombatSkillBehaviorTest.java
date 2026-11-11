package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Combat loop: health-aware swing pacing and player safety.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class CombatSkillBehaviorTest extends SkillTestSupport {
    @Test
    public void attackToDeathUsesTheHealthPlanAndPacesSwings() throws Exception {
        final float[] health = new float[]{20.0f};
        final int[] swings = new int[]{0};
        Harness harness = new Harness(library(), state(),
                (name, args) -> {
                    if ("attack_plan".equals(name)) {
                        JsonObject plan = ToolJson.ok();
                        plan.addProperty("found", true);
                        plan.addProperty("entity_id", args.get("entity_id").getAsInt());
                        plan.addProperty("name", "Zombie");
                        plan.addProperty("alive", health[0] > 0);
                        plan.addProperty("player", false);
                        plan.addProperty("health", health[0]);
                        plan.addProperty("total_health", health[0]);
                        plan.addProperty("can_damage", true);
                        plan.addProperty("attacks_to_kill", 3);
                        plan.addProperty("damage_per_hit", 7);
                        plan.addProperty("hurt_resistant_ticks", 20);
                        plan.addProperty("immune", false);
                        plan.addProperty("next_attack_in_ms", 0);
                        return plan;
                    }
                    if ("attack_entity".equals(name)) {
                        swings[0]++;
                        health[0] = Math.max(0.0f, health[0] - 7.0f);
                        JsonObject hit = ToolJson.ok();
                        hit.addProperty("killed", health[0] <= 0.0f);
                        hit.addProperty("healthAfter", health[0]);
                        hit.addProperty("damageDealt", 7);
                        hit.addProperty("nextAttackInMs", 0);
                        return hit;
                    }
                    return null;
                });
        JsonObject result = harness.run("attack_to_death", "{entity_id:42,max_seconds:5}");
        assertTrue("the loop must finish the target: " + result,
                result.get("killed").getAsBoolean());
        assertEquals("a 20 HP zombie with a 7-damage sword needs three hits",
                3, result.get("attacks").getAsInt());
        assertEquals("the loop must not overswing", 3, swings[0]);

        for (Call call : harness.calls) {
            if ("attack_entity".equals(call.name)) {
                assertEquals("the plan's entity id is used",
                        42, call.args.get("entity_id").getAsInt());
            }
        }
    }

    @Test
    public void attackToDeathRefusesPlayersAndReportsBlockers() throws Exception {
        Harness harness = new Harness(library(), state(),
                (name, args) -> {
                    if (!"attack_plan".equals(name)) return null;
                    JsonObject plan = ToolJson.ok();
                    plan.addProperty("found", true);
                    plan.addProperty("entity_id", args.get("entity_id").getAsInt());
                    plan.addProperty("name", "Tester");
                    plan.addProperty("alive", true);
                    plan.addProperty("player", true);
                    plan.addProperty("can_damage", false);
                    plan.addProperty("blocker", "players are never attacked");
                    return plan;
                });
        JsonObject result = harness.run("attack_to_death", "{entity_id:7}");
        assertFalse("a player must never be killed by the loop",
                result.get("killed").getAsBoolean());
        assertTrue("the blocker must explain why", result.get("blocker").getAsString()
                .toLowerCase().contains("player"));
        for (Call call : harness.calls) {
            assertNotEquals("no swing may reach a player", "attack_entity", call.name);
        }
    }
}
