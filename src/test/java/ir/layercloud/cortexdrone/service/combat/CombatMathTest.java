package ir.layercloud.cortexdrone.service.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Vanilla 1.7.10 combat arithmetic.
 *
 * <p>The formulas drive the model's attack plan, so a wrong armor or
 * hurt-resistance constant would make every forecast lie. These tests pin the
 * exact rules from {@code EntityLivingBase}.</p>
 */
public class CombatMathTest {

    @Test
    public void armorReducesDamageBeforeResistance() {
        // 10 raw with 10 armor: 10 * (25 - 10) / 25 = 6.0
        assertEquals(6.0f, CombatMath.mitigatedDamage(10.0f, 10, 0), 0.0001f);
        // Then Resistance I removes another 5/25: 6.0 * 20 / 25 = 4.8
        assertEquals(4.8f, CombatMath.mitigatedDamage(10.0f, 10, 1), 0.0001f);
    }

    @Test
    public void armorIsClampedToTheVanillaTwentyFiveCap() {
        assertEquals(0.0f, CombatMath.mitigatedDamage(7.0f, 99, 0), 0.0001f);
        // Resistance V would exceed the cap; it must not go negative.
        assertEquals(0.0f, CombatMath.mitigatedDamage(7.0f, 0, 5), 0.0001f);
    }

    @Test
    public void unarmoredFistMatchesVanilla() {
        assertEquals(1.0f, CombatMath.mitigatedDamage(1.0f, 0, 0), 0.0001f);
        assertEquals(0.0f, CombatMath.mitigatedDamage(0.0f, 0, 0), 0.0001f);
    }

    @Test
    public void attacksToKillRoundsUpAndReportsImpossible() {
        // A 20 HP zombie with a 7-damage sword: ceil(20 / 7) = 3 hits.
        assertEquals(3, CombatMath.attacksToKill(20.0f, 7.0f));
        assertEquals(1, CombatMath.attacksToKill(0.5f, 1.0f));
        assertEquals(20, CombatMath.attacksToKill(20.0f, 1.0f));
        assertEquals("dead targets need no hits", 0, CombatMath.attacksToKill(0.0f, 5.0f));
        assertEquals("zero damage can never finish a target", -1,
                CombatMath.attacksToKill(20.0f, 0.0f));
    }

    @Test
    public void hurtWaitUsesOnlyTheRemainingImmuneWindow() {
        // maxHurtResistantTime is 20; the target is hittable again at <= 10.
        assertEquals("a full window is 10 ticks of wait (500 ms)",
                500, CombatMath.hurtWaitMs(20, 20, 0));
        assertEquals("exactly the threshold needs no wait",
                0, CombatMath.hurtWaitMs(10, 20, 0));
        assertEquals("an expired window needs no wait",
                0, CombatMath.hurtWaitMs(0, 20, 0));
        assertEquals("15 ticks left is 5 over the threshold",
                250, CombatMath.hurtWaitMs(15, 20, 0));
    }

    @Test
    public void hurtWaitNeverUnderlapsTheActionCooldown() {
        // The action also refuses to swing for 4 ticks (200 ms) after a hit.
        assertEquals(200, CombatMath.hurtWaitMs(0, 20, CombatMath.ATTACK_COOLDOWN_TICKS));
        // Whichever is longer wins.
        assertEquals(500, CombatMath.hurtWaitMs(20, 20, CombatMath.ATTACK_COOLDOWN_TICKS));
    }

    @Test
    public void estimatedSecondsScalesWithHitsAndCadence() {
        assertEquals(0.0, CombatMath.estimatedSeconds(0, 500), 0.0001);
        assertEquals(1.5, CombatMath.estimatedSeconds(3, 500), 0.0001);
        // A cadence below one tick is raised to one tick.
        assertEquals(0.15, CombatMath.estimatedSeconds(3, 0), 0.0001);
    }
}
