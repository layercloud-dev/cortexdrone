package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.service.world.WorldBounds;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The navigation status snapshot is presentation only and must stay pure.
 */
public class NavigationStatusTest {
    @Test
    public void idleIsNotActive() {
        NavigationStatus status = NavigationStatus.idle();
        assertFalse(status.isActive());
        assertEquals("idle", status.getPhase());
        assertFalse(status.isFlying());
    }

    @Test
    public void missingSessionOrStateFallsBackToIdle() {
        assertFalse(NavigationStatus.of(null, null).isActive());
    }

    @Test
    public void flyingIsDerivedFromPhase() {
        NavigationStatus flying = NavigationStatus.forDrone(true, "flight", "direct", 1, 2, 3);
        assertTrue(flying.isActive());
        assertTrue(flying.isFlying());
        assertEquals(1.0, flying.getGoalX(), 0.0001);

        NavigationStatus planning = NavigationStatus.forDrone(true, "plan", "direct", 1, 2, 3);
        assertTrue(planning.isActive());
        assertFalse(planning.isFlying());
    }

    @Test
    public void describeIncludesTheReasonOnlyWhenPresent() {
        NavigationStatus withReason = NavigationStatus.forDrone(true, "flight", "tunnel", 0, 0, 0);
        assertTrue(withReason.describe(null).contains("tunnel"));

        NavigationStatus withoutReason = NavigationStatus.forDrone(true, "flight", "", 0, 0, 0);
        assertFalse(withoutReason.describe(null).contains("("));
    }

    @Test
    public void worldBoundsAgreeWithTheWorldHeight() {
        assertTrue(WorldBounds.inWorld(WorldBounds.MIN_Y));
        assertTrue(WorldBounds.inWorld(WorldBounds.MAX_Y));
        assertFalse(WorldBounds.inWorld(WorldBounds.MIN_Y - 1));
        assertFalse(WorldBounds.inWorld(WorldBounds.MAX_Y + 1));
    }
}
