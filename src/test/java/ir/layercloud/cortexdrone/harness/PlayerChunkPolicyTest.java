package ir.layercloud.cortexdrone.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerChunkPolicyTest {
    @Test
    public void chunksInsidePlayerViewArePlayerOwned() {
        assertTrue(PlayerChunkPolicy.isWithinViewDistance(10, -4, 10, -4, 10));
        assertTrue(PlayerChunkPolicy.isWithinViewDistance(10, -4, 0, 6, 10));
        assertTrue(PlayerChunkPolicy.isWithinViewDistance(10, -4, 20, -14, 10));
    }

    @Test
    public void chunksOutsidePlayerViewAreDroneOwned() {
        assertFalse(PlayerChunkPolicy.isWithinViewDistance(10, -4, 21, -4, 10));
        assertFalse(PlayerChunkPolicy.isWithinViewDistance(10, -4, 10, 7, 10));
        assertFalse(PlayerChunkPolicy.isWithinViewDistance(10, -4, -1, -15, 10));
    }

    @Test
    public void viewDistanceIsSquareNotCircular() {
        // Opposite corners of the square view are still player-owned.
        assertTrue(PlayerChunkPolicy.isWithinViewDistance(0, 0, 10, 10, 10));
        assertTrue(PlayerChunkPolicy.isWithinViewDistance(0, 0, -10, -10, 10));
    }
}
