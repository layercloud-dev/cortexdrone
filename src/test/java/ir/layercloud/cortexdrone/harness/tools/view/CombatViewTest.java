package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.combat.CombatAssessment;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The combat JSON contract the model and skills read.
 *
 * <p>{@code attack_to_death} branches on {@code ok}, {@code player},
 * {@code can_damage}, {@code total_health}, {@code next_attack_in_ms} and
 * {@code blocker}; renaming any of them would silently break combat.</p>
 */
public class CombatViewTest {
    private static CombatAssessment zombie() {
        return CombatAssessment.builder()
                .found(true)
                .entityId(42)
                .name("Zombie")
                .type("EntityZombie")
                .alive(true)
                .hostile(true)
                .attackable(true)
                .health(12.0f)
                .maxHealth(20.0f)
                .absorption(2.0f)
                .totalHealth(14.0f)
                .armor(5)
                .resistanceLevel(1)
                .hurtResistantTime(15)
                .immune(true)
                .nextAttackInMs(250)
                .x(10.0)
                .y(64.0)
                .z(-3.0)
                .distance(3.2)
                .inReach(true)
                .weaponAvailable(true)
                .weapon("Iron Sword")
                .weaponSlot(2)
                .rawDamage(7.0f)
                .damagePerHit(4.48f)
                .canDamage(true)
                .attacksToKill(4)
                .estimatedSeconds(1.0)
                .build();
    }

    @Test
    public void healthCarriesThePoolAndTheImmunityWindow() {
        JsonObject json = CombatView.health(zombie());
        assertTrue(json.get("ok").getAsBoolean());
        assertEquals(42, json.get("entity_id").getAsInt());
        assertEquals("Zombie", json.get("name").getAsString());
        assertEquals(12.0f, json.get("health").getAsFloat(), 0.0001f);
        assertEquals(2.0f, json.get("absorption").getAsFloat(), 0.0001f);
        assertEquals("absorption is part of the kill pool",
                14.0f, json.get("total_health").getAsFloat(), 0.0001f);
        assertEquals(5, json.get("armor").getAsInt());
        assertEquals(1, json.get("resistance_level").getAsInt());
        assertTrue("a hit right now would be ignored", json.get("immune").getAsBoolean());
        assertEquals(250, json.get("next_attack_in_ms").getAsInt());
        assertTrue(json.get("in_reach").getAsBoolean());
    }

    @Test
    public void planAddsWeaponAndKillMathWithoutLosingTheHealthFields() {
        JsonObject json = CombatView.plan(zombie());
        assertEquals(4, json.get("attacks_to_kill").getAsInt());
        assertEquals(4.48f, json.get("damage_per_hit").getAsFloat(), 0.0001f);
        assertEquals(7.0f, json.get("raw_damage").getAsFloat(), 0.0001f);
        assertEquals("Iron Sword", json.get("weapon").getAsString());
        assertEquals(2, json.get("weapon_slot").getAsInt());
        assertTrue(json.get("can_damage").getAsBoolean());
        assertTrue("the plan repeats the health fields so one call is enough",
                json.has("health") && json.has("total_health"));
        assertTrue(json.get("hint").getAsString().contains("hurt-resistance"));
    }

    @Test
    public void missingEntityIsAnErrorEnvelopeNotAnEmptySuccess() {
        JsonObject json = CombatView.health(CombatAssessment.notFound(99));
        assertFalse(json.get("ok").getAsBoolean());
        assertEquals("entity_not_found", json.get("code").getAsString());
        assertEquals(99, json.get("entity_id").getAsInt());
        assertTrue(json.get("retryable").getAsBoolean());
    }

    @Test
    public void blockedTargetReportsWhyMoreSwingsCannotHelp() {
        CombatAssessment blocked = CombatAssessment.builder()
                .found(true)
                .entityId(7)
                .name("Tester")
                .alive(true)
                .player(true)
                .health(20.0f)
                .totalHealth(20.0f)
                .canDamage(false)
                .blocker("players are never attacked")
                .attacksToKill(-1)
                .build();
        JsonObject json = CombatView.plan(blocked);
        assertTrue("the snapshot itself is valid", json.get("ok").getAsBoolean());
        assertTrue(json.get("player").getAsBoolean());
        assertFalse(json.get("can_damage").getAsBoolean());
        assertEquals("players are never attacked", json.get("blocker").getAsString());
        assertEquals(-1, json.get("attacks_to_kill").getAsInt());
    }
}
