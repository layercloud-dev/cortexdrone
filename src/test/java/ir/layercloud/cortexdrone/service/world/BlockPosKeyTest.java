package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * One packing layout for every block-keyed map.
 *
 * <p>Regression risk: three copies existed, one with a different bit layout.
 * A key produced by one module and read by another silently resolved to a
 * different block.</p>
 */
public class BlockPosKeyTest {
    @Test
    public void roundTripsNormalCoordinates() {
        assertRoundTrip(0, 0, 0);
        assertRoundTrip(1, 64, -1);
        assertRoundTrip(-30000000, 0, 30000000);
        assertRoundTrip(30000000, 255, -30000000);
        assertRoundTrip(-1234, 128, 5678);
    }

    @Test
    public void negativeCoordinatesDoNotCollideWithPositiveOnes() {
        assertNotEquals(BlockPosKey.pack(5, 64, 5), BlockPosKey.pack(-5, 64, 5));
        assertNotEquals(BlockPosKey.pack(5, 64, 5), BlockPosKey.pack(5, 64, -5));
        assertNotEquals(BlockPosKey.pack(5, 64, 5), BlockPosKey.pack(5, 65, 5));
    }

    @Test
    public void adjacentPositionsProduceDistinctKeys() {
        long base = BlockPosKey.pack(100, 64, 100);
        assertNotEquals(base, BlockPosKey.pack(101, 64, 100));
        assertNotEquals(base, BlockPosKey.pack(100, 65, 100));
        assertNotEquals(base, BlockPosKey.pack(100, 64, 101));
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long key = BlockPosKey.pack(x, y, z);
        assertEquals("x for " + x + "," + y + "," + z, x, BlockPosKey.unpackX(key));
        assertEquals("y for " + x + "," + y + "," + z, y, BlockPosKey.unpackY(key));
        assertEquals("z for " + x + "," + y + "," + z, z, BlockPosKey.unpackZ(key));
    }
}
