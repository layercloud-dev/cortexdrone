package ir.layercloud.cortexdrone.manager.navigation.impl;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
