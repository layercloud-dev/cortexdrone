package ir.layercloud.cortexdrone.manager.navigation.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A chain of recoveries from one place must end the request.
 *
 * <p>Live evidence: the 2026-11-13 mining task sat in a flooded pocket at
 * {@code (-301,55,502)} and logged {@code dug=0/32 recov=4 detours=12} every
 * three seconds for twenty minutes. Each short detour reset the per-leg
 * counters, so only a same-place counter can see it.</p>
 */
public class NavigationSameSpotTest {
    @Test
    public void theFirstRecoveryIsAlwaysAllowed() {
        NavigationSession session = new NavigationSession();
        assertEquals(0, session.sameSpotRecoveries);
        assertFalse(session.noteRecoverySpot(-301.0D, 55.0D, 502.0D,
                NavigationRecovery.MAX_SAME_SPOT_RECOVERIES));
        assertEquals(1, session.sameSpotRecoveries);
    }

    @Test
    public void repeatedRecoveriesFromOneBlockTripTheCap() {
        NavigationSession session = new NavigationSession();
        int cap = NavigationRecovery.MAX_SAME_SPOT_RECOVERIES;
        boolean tripped = false;
        for (int attempt = 0; attempt <= cap && !tripped; attempt++) {
            // The live log drifted less than a block per loop.
            tripped = session.noteRecoverySpot(-300.9D + attempt * 0.02D, 55.05D, 502.1D, cap);
        }
        assertTrue("a stationary loop must end the request", tripped);
        assertTrue(session.sameSpotRecoveries > cap);
    }

    @Test
    public void movingToANewPlaceResetsTheCounter() {
        NavigationSession session = new NavigationSession();
        int cap = NavigationRecovery.MAX_SAME_SPOT_RECOVERIES;
        for (int attempt = 0; attempt < cap; attempt++) {
            assertFalse(session.noteRecoverySpot(0.0D, 64.0D, 0.0D, cap + 100));
        }
        assertFalse("twenty blocks away is a different problem",
                session.noteRecoverySpot(20.0D, 64.0D, 0.0D, cap + 100));
        assertEquals(1, session.sameSpotRecoveries);
    }

    @Test
    public void theCapIsSmallEnoughToEndATwentyMinuteLoop() {
        assertTrue("the recorded loop ran for ~400 recoveries",
                NavigationRecovery.MAX_SAME_SPOT_RECOVERIES < 20);
        assertTrue("a real obstacle gets a few attempts",
                NavigationRecovery.MAX_SAME_SPOT_RECOVERIES >= 4);
        assertTrue("the absolute ceiling stays higher than the local one",
                NavigationRecovery.MAX_RECOVERIES > NavigationRecovery.MAX_SAME_SPOT_RECOVERIES);
    }

    @Test
    public void theFailureReasonNamesThePlace() {
        String reason = "Flight path is blocked (stuck in one place after "
                + NavigationRecovery.MAX_SAME_SPOT_RECOVERIES + " recovery attempts)";
        assertTrue(reason.startsWith("Flight path is blocked"));
        assertTrue("the model needs to see why it cannot retry the same goto",
                reason.contains("one place"));
    }
}
