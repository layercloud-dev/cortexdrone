package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.combat.CombatAssessment;

/**
 * JSON translation boundary for one combat assessment.
 *
 * <p>Two tools share it: {@code get_entity_health} returns the health block and
 * {@code attack_plan} returns the kill math. Keeping one serializer means both
 * always describe the same live snapshot.</p>
 */
public final class CombatView {
    private CombatView() {
    }

    public static JsonObject health(CombatAssessment assessment) {
        if (assessment == null || !assessment.isFound()) {
            JsonObject missing = ToolJson.error("entity_not_found",
                    "No living entity with that id.",
                    "Call get_entities to list live entity ids, then retry.", true);
            missing.addProperty("entity_id", assessment != null ? assessment.getEntityId() : -1);
            return missing;
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("entity_id", assessment.getEntityId());
        out.addProperty("name", assessment.getName());
        out.addProperty("type", assessment.getType());
        out.addProperty("alive", assessment.isAlive());
        out.addProperty("hostile", assessment.isHostile());
        out.addProperty("animal", assessment.isAnimal());
        out.addProperty("player", assessment.isPlayer());
        out.addProperty("attackable", assessment.isAttackable());
        out.addProperty("health", assessment.getHealth());
        out.addProperty("max_health", assessment.getMaxHealth());
        out.addProperty("absorption", assessment.getAbsorption());
        out.addProperty("total_health", assessment.getTotalHealth());
        out.addProperty("armor", assessment.getArmor());
        out.addProperty("resistance_level", assessment.getResistanceLevel());
        out.addProperty("hurt_resistant_ticks", assessment.getHurtResistantTime());
        out.addProperty("immune", assessment.isImmune());
        out.addProperty("next_attack_in_ms", assessment.getNextAttackInMs());
        out.addProperty("x", assessment.getX());
        out.addProperty("y", assessment.getY());
        out.addProperty("z", assessment.getZ());
        out.addProperty("distance", assessment.getDistance());
        out.addProperty("in_reach", assessment.isInReach());
        out.addProperty("attacks_to_kill", assessment.getAttacksToKill());
        if (assessment.getBlocker() != null) out.addProperty("blocker", assessment.getBlocker());
        return out;
    }

    public static JsonObject plan(CombatAssessment assessment) {
        JsonObject out = health(assessment);
        if (!out.get("ok").getAsBoolean()) return out;
        out.addProperty("weapon", assessment.getWeapon());
        out.addProperty("weapon_slot", assessment.getWeaponSlot());
        out.addProperty("weapon_available", assessment.isWeaponAvailable());
        out.addProperty("raw_damage", assessment.getRawDamage());
        out.addProperty("damage_per_hit", assessment.getDamagePerHit());
        out.addProperty("can_damage", assessment.isCanDamage());
        out.addProperty("estimated_seconds", assessment.getEstimatedSeconds());
        out.addProperty("hint", assessment.isCanDamage()
                ? "Attack " + assessment.getAttacksToKill()
                + " time(s), waiting next_attack_in_ms between hits"
                + (assessment.isImmune()
                ? "; the target is inside its hurt-resistance window right now"
                : "")
                + ". Early hits inside that window are ignored."
                : "This target cannot be finished with the current weapon"
                + (assessment.getBlocker() != null ? ": " + assessment.getBlocker() : "."));
        return out;
    }
}
