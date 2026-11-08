package ir.layercloud.cortexdrone.service.navigation;

import ir.layercloud.cortexdrone.config.ModConfig;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Speed policy: the ceiling is 5x the tuned cruise speed, and nothing may
 * exceed it.
 *
 * <p>These are safety rules as much as speed rules. A drone that flies 5x
 * faster but keeps the old braking distance, arrival radius or watchdog budget
 * either overshoots forever or gets declared "stuck" while it is actually the
 * fastest thing in the world.</p>
 */
public class DroneSpeedTest {

    @Test
    public void defaultMultiplierKeepsTheTunedSpeedsExactly() {
        // 1.0 must reproduce the historical tiers, not "close to" them, so an
        // untouched config behaves byte-for-byte like the old code.
        assertEquals(DroneSpeed.FAR, DroneSpeed.resolve(100.0, 1.0, 0.0, false), 1e-9);
        assertEquals(DroneSpeed.MID, DroneSpeed.resolve(20.0, 1.0, 0.0, false), 1e-9);
        assertEquals(DroneSpeed.NEAR, DroneSpeed.resolve(5.0, 1.0, 0.0, false), 1e-9);
    }

    @Test
    public void fiveTimesIsTheDefinedCeiling() {
        double far = DroneSpeed.resolve(100.0, 5.0, 0.0, false);
        assertEquals(DroneSpeed.FAR * 5.0, far, 1e-9);
        assertEquals(DroneSpeed.HARD_CAP, far, 1e-9);
        // 5x of 0.75 is 3.75 blocks/tick = 75 blocks/second.
        assertEquals(3.75, far, 1e-9);
    }

    @Test
    public void requestsAboveTheCeilingAreClampedNeverAccepted() {
        assertEquals(DroneSpeed.HARD_CAP, DroneSpeed.resolve(100.0, 50.0, 0.0, false), 1e-9);
        assertEquals(DroneSpeed.HARD_CAP, DroneSpeed.resolve(100.0, 1.0, 100.0, false), 1e-9);
        assertEquals(DroneSpeed.MAX_MULTIPLIER, DroneSpeed.clampMultiplier(1000.0), 1e-9);
    }

    @Test
    public void aPerCallValueOverridesTheConfigAndIsClamped() {
        // Config 1x, request 3x: the request wins.
        assertEquals(DroneSpeed.FAR * 3.0, DroneSpeed.resolve(100.0, 1.0, 3.0, false), 1e-9);
        // Request below the floor is raised to it, not rejected.
        assertEquals(DroneSpeed.FAR * DroneSpeed.MIN_MULTIPLIER,
                DroneSpeed.resolve(100.0, 1.0, 0.01, false), 1e-9);
        // A NaN request falls back to the config, never to NaN velocity.
        assertEquals(DroneSpeed.FAR, DroneSpeed.resolve(100.0, 1.0, Double.NaN, false), 1e-9);
    }

    @Test
    public void aCarriedPassengerNeverGetsABoost() {
        // Even a 5x config resolves to the tuned speed with a rider: their head
        // is 1.8 blocks above the drone and the extra closing speed kills.
        assertEquals(DroneSpeed.FAR, DroneSpeed.resolve(100.0, 5.0, 0.0, true), 1e-9);
        assertEquals(DroneSpeed.NEAR, DroneSpeed.resolve(5.0, 5.0, 0.0, true), 1e-9);
    }

    @Test
    public void slowMultipliersAreSupportedForCarefulWork() {
        assertEquals(DroneSpeed.FAR * 0.5, DroneSpeed.resolve(100.0, 0.5, 0.0, false), 1e-9);
        assertEquals(DroneSpeed.MIN_MULTIPLIER, DroneSpeed.clampMultiplier(0.0), 1e-9);
    }

    @Test
    public void arrivalAndWaypointTolerancesScaleSubLinearly() {
        // At 5x the drone must accept a wider arrival so it does not orbit,
        // but not so wide that "arrived" is four blocks away.
        assertEquals(0.85, DroneSpeed.arrivalRadius(1.0), 1e-9);
        assertEquals(0.85 * Math.sqrt(5.0), DroneSpeed.arrivalRadius(5.0), 1e-9);
        assertTrue("arrival must still be precise: " + DroneSpeed.arrivalRadius(5.0),
                DroneSpeed.arrivalRadius(5.0) < 2.5);
        assertEquals(0.75, DroneSpeed.waypointRadius(1.0), 1e-9);
        assertEquals(0.75 * Math.sqrt(5.0), DroneSpeed.waypointRadius(5.0), 1e-9);
        // Never wider than the hard ceiling allows.
        assertEquals(0.85 * Math.sqrt(5.0), DroneSpeed.arrivalRadius(500.0), 1e-9);
    }

    @Test
    public void cooldownsNeverShortenBelowTheTunedValue() {
        assertEquals("a slow drone stays on the tuned replan cadence",
                8, DroneSpeed.repathTicks(8, 1.0));
        assertTrue("a faster drone must replan more often, not less",
                DroneSpeed.repathTicks(8, 5.0) <= 8);
        assertEquals("the fastest drone still replans on the floor",
                DroneSpeed.REPATH_FLOOR, DroneSpeed.repathTicks(8, 5.0));
        assertEquals(16, DroneSpeed.repathTicks(8, 0.5));
    }

    @Test
    public void tiersMatchTheHistoricalDistances() {
        assertEquals("far", DroneSpeed.describe(100.0));
        assertEquals("far", DroneSpeed.describe(24.01));
        assertEquals("mid", DroneSpeed.describe(24.0));
        assertEquals("mid", DroneSpeed.describe(14.01));
        assertEquals("near", DroneSpeed.describe(14.0));
        assertEquals("near", DroneSpeed.describe(1.0));
    }

    @Test
    public void configDefaultsToTheFastMultiplierAndIsClamped() {
        ModConfig config = new ModConfig();
        assertEquals("a fresh install flies at the requested 5x",
                DroneSpeed.MAX_MULTIPLIER, config.getDroneSpeedMultiplier(), 1e-9);
        assertEquals(DroneSpeed.MAX_MULTIPLIER, DroneSpeed.configured(config), 1e-9);

        config.setDroneSpeedMultiplier(99.0);
        assertEquals("a hand-edited config cannot exceed the ceiling",
                DroneSpeed.MAX_MULTIPLIER, DroneSpeed.configured(config), 1e-9);

        config.setDroneSpeedMultiplier(-3.0);
        assertEquals("a negative value falls back to the floor",
                DroneSpeed.MIN_MULTIPLIER, DroneSpeed.configured(config), 1e-9);

        config.setDroneSpeedMultiplier(1.0);
        assertEquals("1x is always available as the tuned baseline",
                1.0, DroneSpeed.configured(config), 1e-9);
    }

    @Test
    public void sanitizeKeepsAnExistingNormalSpeedUnchanged() {
        ModConfig config = new ModConfig();
        config.setDroneSpeedMultiplier(1.0);
        config.sanitized();
        assertEquals("a user who chose 1x must keep 1x across restarts",
                1.0, config.getDroneSpeedMultiplier(), 1e-9);
    }

    @Test
    public void sanitizeRepairsAnOutOfRangeConfig() {
        ModConfig config = new ModConfig();
        config.setDroneSpeedMultiplier(10_000.0);
        config.sanitized();
        assertEquals(DroneSpeed.MAX_MULTIPLIER, config.getDroneSpeedMultiplier(), 1e-9);

        ModConfig zero = new ModConfig();
        zero.setDroneSpeedMultiplier(0.0);
        zero.sanitized();
        assertEquals("zero would freeze the drone; it is repaired to normal",
                1.0, zero.getDroneSpeedMultiplier(), 1e-9);
    }

    @Test
    public void aNullConfigFallsBackToNormalSpeed() {
        assertEquals(DroneSpeed.DEFAULT_MULTIPLIER, DroneSpeed.configured(null), 1e-9);
    }
}
