package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import static org.junit.Assert.*;

/** Build-site geometry rules that do not need a live world. */
public class BuildSiteFinderTest {
    @Test
    public void playerClearanceIsSymmetric() {
        // A 5x5 pad from (0,0) to (4,4); keep 2 blocks clear on every side.
        assertTrue(BuildSiteFinder.overlapsPlayer(2.0, 2.0, 0, 0, 4, 4));
        assertTrue("2 blocks north is inside the clearance",
                BuildSiteFinder.overlapsPlayer(2.0, -2.0, 0, 0, 4, 4));
        assertTrue("2 blocks south is inside the clearance",
                BuildSiteFinder.overlapsPlayer(2.0, 6.0, 0, 0, 4, 4));
        assertTrue("2 blocks west is inside the clearance",
                BuildSiteFinder.overlapsPlayer(-2.0, 2.0, 0, 0, 4, 4));
        assertTrue("2 blocks east is inside the clearance",
                BuildSiteFinder.overlapsPlayer(6.0, 2.0, 0, 0, 4, 4));
    }

    @Test
    public void outsideTheClearanceDoesNotOverlap() {
        assertFalse(BuildSiteFinder.overlapsPlayer(2.0, -3.0, 0, 0, 4, 4));
        assertFalse(BuildSiteFinder.overlapsPlayer(2.0, 7.0, 0, 0, 4, 4));
        assertFalse(BuildSiteFinder.overlapsPlayer(-3.0, 2.0, 0, 0, 4, 4));
        assertFalse(BuildSiteFinder.overlapsPlayer(7.0, 2.0, 0, 0, 4, 4));
    }

    @Test
    public void clearanceIsTheSameOnEverySide() {
        // Regression: the old check used x1-1..x2+2, keeping 1 block on the
        // min side and 2 on the max side.
        assertTrue(BuildSiteFinder.overlapsPlayer(-2.0, 0.0, 0, 0, 4, 4));
        assertTrue(BuildSiteFinder.overlapsPlayer(0.0, -2.0, 0, 0, 4, 4));
    }

    @Test
    public void footprintBoundsAreStable() {
        assertTrue(BuildSiteFinder.MIN_SIDE < BuildSiteFinder.MAX_SIDE);
        assertTrue(BuildSiteFinder.SCHEMA_MIN_RADIUS < BuildSiteFinder.SCHEMA_MAX_RADIUS);
        assertEquals(2, BuildSiteFinder.PLAYER_CLEARANCE);
    }
}
