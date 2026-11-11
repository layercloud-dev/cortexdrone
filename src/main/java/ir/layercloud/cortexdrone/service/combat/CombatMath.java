package ir.layercloud.cortexdrone.service.combat;

import lombok.experimental.UtilityClass;

/**
 * Pure combat arithmetic shared by the assessment service and its tests.
 *
 * <p>The formulas mirror vanilla 1.7.10 {@code EntityLivingBase}: armor first
 * ({@code (25 - armor) / 25}), then the resistance potion
 * ({@code (25 - 5 * level) / 25}), then absorption. Damage is never negative.
 * The hurt-resistance wait comes from {@code attackEntityFrom}: a hit is
 * ignored while {@code hurtResistantTime > maxHurtResistantTime / 2} unless it
 * is stronger than {@code lastDamage}.</p>
 */
@UtilityClass
public final class CombatMath {
    /**
     * Vanilla armor cap used by the damage formula.
     */
    public static final int ARMOR_CAP = 25;
    /**
     * The interaction action refuses an attack for this many ticks after a hit.
     */
    public static final int ATTACK_COOLDOWN_TICKS = 4;
    public static final int TICK_MS = 50;

    public static float mitigatedDamage(float rawDamage, int armor, int resistanceLevel) {
        if (rawDamage <= 0.0f) return 0.0f;
        float damage = rawDamage;
        int armorValue = Math.max(0, Math.min(ARMOR_CAP, armor));
        damage = damage * (float) (ARMOR_CAP - armorValue) / (float) ARMOR_CAP;
        if (resistanceLevel > 0) {
            int reduction = Math.min(ARMOR_CAP, resistanceLevel * 5);
            damage = damage * (float) (ARMOR_CAP - reduction) / (float) ARMOR_CAP;
        }
        return Math.max(0.0f, damage);
    }

    /**
     * Full hits required to remove the health pool.
     *
     * @return 0 when already dead, -1 when a hit cannot deal damage
     */
    public static int attacksToKill(float totalHealth, float damagePerHit) {
        if (totalHealth <= 0.0f) return 0;
        if (damagePerHit <= 0.0f) return -1;
        return (int) Math.ceil(totalHealth / damagePerHit);
    }

    /**
     * Milliseconds to wait before the next full-damage hit. This covers both
     * the target's hurt-resistance window and the action's own cooldown.
     */
    public static int hurtWaitMs(int hurtResistantTime, int maxHurtResistantTime,
                                 int attackCooldownTicks) {
        int threshold = Math.max(0, maxHurtResistantTime / 2);
        int waitTicks = Math.max(0, hurtResistantTime - threshold);
        waitTicks = Math.max(waitTicks, Math.max(0, attackCooldownTicks));
        return waitTicks * TICK_MS;
    }

    /**
     * Rough wall-clock estimate for the attacks, ignoring travel time.
     */
    public static double estimatedSeconds(int attacksToKill, int waitMs) {
        if (attacksToKill <= 0) return 0.0;
        long cadence = Math.max(TICK_MS, waitMs);
        return attacksToKill * (double) cadence / 1000.0;
    }
}
