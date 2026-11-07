package ir.layercloud.cortexdrone.manager.chunk;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerChunkOwnershipTest {
    @Test
    public void chunksInsidePlayerViewArePlayerOwned() {
        assertTrue(PlayerChunkOwnership.isWithinViewDistance(10, -4, 10, -4, 10));
        assertTrue(PlayerChunkOwnership.isWithinViewDistance(10, -4, 0, 6, 10));
        assertTrue(PlayerChunkOwnership.isWithinViewDistance(10, -4, 20, -14, 10));
    }

    @Test
    public void chunksOutsidePlayerViewAreDroneOwned() {
        assertFalse(PlayerChunkOwnership.isWithinViewDistance(10, -4, 21, -4, 10));
        assertFalse(PlayerChunkOwnership.isWithinViewDistance(10, -4, 10, 7, 10));
        assertFalse(PlayerChunkOwnership.isWithinViewDistance(10, -4, -1, -15, 10));
    }

    @Test
    public void viewDistanceIsSquareNotCircular() {
        // Vanilla PlayerManager watches a square, not a circle.
        assertTrue(PlayerChunkOwnership.isWithinViewDistance(0, 0, 10, 10, 10));
        assertTrue(PlayerChunkOwnership.isWithinViewDistance(0, 0, -10, -10, 10));
    }
}
