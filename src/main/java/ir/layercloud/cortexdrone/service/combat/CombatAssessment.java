package ir.layercloud.cortexdrone.service.combat;

import lombok.Builder;
import lombok.Value;

/**
 * Read-only snapshot of one target's health and the drone's ability to kill it.
 *
 * <p>All damage numbers are for a single landed melee hit. {@code health} is the
 * current health, {@code absorption} is the extra damage buffer and
 * {@code totalHealth} is the sum that must be removed. {@code damagePerHit} is
 * the weapon's raw damage after the target's armor and resistance potion.
 * {@code attacksToKill} is {@code ceil(totalHealth / damagePerHit)}; it is
 * {@code -1} when the target cannot currently be damaged and {@code 0} when it
 * is already dead.</p>
 */
@Value
@Builder
public class CombatAssessment {
    boolean found;
    int entityId;
    String name;
    String type;
    boolean alive;
    boolean hostile;
    boolean animal;
    boolean player;

    /**
     * True when policy allows an attack: alive and not a player.
     */
    boolean attackable;

    float health;
    float maxHealth;
    float absorption;
    float totalHealth;
    int armor;
    /**
     * Potion resistance level: 0 = none, 1 = Resistance I, ...
     */
    int resistanceLevel;
    int hurtResistantTime;
    /**
     * True while a hit would be ignored by the hurt-resistance window.
     */
    boolean immune;
    int nextAttackInMs;

    double x;
    double y;
    double z;
    double distance;
    boolean inReach;

    boolean weaponAvailable;
    String weapon;
    int weaponSlot;
    float rawDamage;
    float damagePerHit;
    boolean canDamage;
    int attacksToKill;
    double estimatedSeconds;
    /**
     * Human-readable reason the target cannot be finished, or {@code null}.
     */
    String blocker;

    public static CombatAssessment notFound(int entityId) {
        return builder()
                .found(false)
                .entityId(entityId)
                .weaponSlot(-1)
                .attacksToKill(-1)
                .build();
    }
}
