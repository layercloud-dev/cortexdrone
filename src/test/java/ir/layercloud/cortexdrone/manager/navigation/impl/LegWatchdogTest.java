package ir.layercloud.cortexdrone.manager.navigation.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Guards the Baritone-style per-leg timeout.
 *
 * <p>The live logs showed a drone wedged at (-1214.50, 60.75, -948.38) for
 * minutes while the navigation session stayed "active" and every A* replan
 * produced the same blocked waypoint. The watchdog is what turns that silent
 * spin into a recovery attempt.</p>
 */
public class LegWatchdogTest {

    @Test
    public void flagsAPhysicallyWedgedLeg() {
        LegWatchdog watchdog = new LegWatchdog();
        watchdog.begin(20.0);
        boolean tripped = false;
        for (int i = 0; i < LegWatchdog.STUCK_TICKS; i++) {
            tripped = watchdog.tick(19.0, 0.0);
        }
        assertTrue("no displacement for 40 ticks must abort the leg", tripped);
    }

    @Test
    public void flagsAMovingButNonProgressingLeg() {
        LegWatchdog watchdog = new LegWatchdog();
        watchdog.begin(20.0);
        boolean tripped = false;
        for (int i = 0; i < LegWatchdog.NO_PROGRESS_TICKS; i++) {
            tripped = watchdog.tick(19.9, 0.04);
        }
        assertTrue("circling without closing the gap must abort the leg", tripped);
    }

    @Test
    public void doesNotAbortAHealthyLeg() {
        LegWatchdog watchdog = new LegWatchdog();
        watchdog.begin(20.0);
        for (int i = 0; i < 100; i++) {
            assertFalse(watchdog.tick(20.0 - i * 0.2, 0.04));
        }
    }

    @Test
    public void progressResetsTheNoProgressCounter() {
        LegWatchdog watchdog = new LegWatchdog();
        watchdog.begin(20.0);
        for (int i = 0; i < 60; i++) {
            assertFalse(watchdog.tick(20.0, 0.04));
        }
        for (int i = 0; i < 60; i++) {
            assertFalse(watchdog.tick(15.0 - i, 0.04));
        }
    }

    @Test
    public void hardBudgetScalesWithLegLength() {
        LegWatchdog shortLeg = new LegWatchdog();
        shortLeg.begin(1.0);
        LegWatchdog longLeg = new LegWatchdog();
        longLeg.begin(200.0);
        assertTrue("a short leg has a short ceiling", longLeg.initialDistance() > shortLeg.initialDistance());
        // A 1-block leg should not need the 2.4k-tick maximum; it also must not
        // fail before its minimum grace period.
        boolean tripped = false;
        for (int i = 0; i < LegWatchdog.MIN_MAX_TICKS - 10; i++) {
            tripped = shortLeg.tick(1.0 - i * 0.005, 0.04);
        }
        assertFalse("a slowly closing short leg must not hit the hard ceiling early", tripped);
    }

    @Test
    public void aFasterDroneGetsAProportionallyShorterBudget() {
        // A short leg is below the hard-ceiling clamp, so the budget is exactly
        // proportional to distance / speed.
        LegWatchdog normal = new LegWatchdog();
        normal.begin(10.0, 1.0);
        LegWatchdog fast = new LegWatchdog();
        fast.begin(10.0, 5.0);
        assertTrue("the boosted budget must be shorter: normal=" + normal.maxTicks()
                        + " fast=" + fast.maxTicks(),
                fast.maxTicks() < normal.maxTicks());
    }

    @Test
    public void aLongLegShrinksItsBudgetWhenBoosted() {
        // 30 blocks x 60 ticks = 1800 at normal speed, 360 at 5x; both are
        // below the MAX_MAX_TICKS clamp so the ratio is visible.
        LegWatchdog normal = new LegWatchdog();
        normal.begin(30.0, 1.0);
        LegWatchdog fast = new LegWatchdog();
        fast.begin(30.0, 5.0);
        assertEquals(1800, normal.maxTicks());
        assertEquals(360, fast.maxTicks());
    }

    @Test
    public void theHardCeilingStillAppliesWhenBoosted() {
        LegWatchdog fast = new LegWatchdog();
        fast.begin(10_000.0, 5.0);
        assertEquals("a huge leg is clamped, never unbounded",
                LegWatchdog.MAX_MAX_TICKS, fast.maxTicks());
    }

    @Test
    public void anOutOfRangeMultiplierIsClamped() {
        // A bad value must not produce a zero or negative tick budget.
        LegWatchdog wild = new LegWatchdog();
        wild.begin(10.0, 1_000.0);
        assertTrue(wild.maxTicks() > 0);
        LegWatchdog negative = new LegWatchdog();
        negative.begin(10.0, -5.0);
        assertTrue(negative.maxTicks() > 0);

        // Both clamp into the supported range, which is still ordered: a
        // clamped-max multiplier shortens the budget, a clamped-min one
        // lengthens it (slow flight legitimately needs more ticks).
        LegWatchdog normal = new LegWatchdog();
        normal.begin(10.0, 1.0);
        assertEquals("1000x clamps to 5x", 160, wild.maxTicks());
        assertTrue("a negative value clamps to the floor and slows the leg",
                negative.maxTicks() >= normal.maxTicks());
    }
}
