package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Vein mining expands one break through face-connected blocks only.
 */
public class VeinFloodTest {
    private static Set<String> keys(List<int[]> points) {
        Set<String> keys = new HashSet<>();
        for (int[] point : points) keys.add(point[0] + "," + point[1] + "," + point[2]);
        return keys;
    }

    /**
     * A plus-shaped cluster in the y=10 plane, plus one isolated block.
     */
    private static VeinFlood.Matches crossAt(int cx, int cy, int cz) {
        return (x, y, z) -> {
            if (y != cy) return false;
            int dx = Math.abs(x - cx);
            int dz = Math.abs(z - cz);
            return dx + dz <= 1;
        };
    }

    @Test
    public void connectedVeinIncludesTheOriginAndFaceNeighbours() {
        List<int[]> vein = VeinFlood.connected(0, 10, 0, 4, 64, crossAt(0, 10, 0));
        assertEquals("origin plus four face neighbours", 5, vein.size());
        assertArrayEqualsInts(new int[]{0, 10, 0}, vein.get(0));
        Set<String> keys = keys(vein);
        assertTrue(keys.contains("1,10,0"));
        assertTrue(keys.contains("-1,10,0"));
        assertTrue(keys.contains("0,10,1"));
        assertTrue(keys.contains("0,10,-1"));
    }

    @Test
    public void aDiagonalBlockIsNotPartOfTheSameVein() {
        VeinFlood.Matches diagonalOnly = (x, y, z) -> y == 10 && x == 1 && z == 1;
        List<int[]> vein = VeinFlood.connected(0, 10, 0, 4, 64, diagonalOnly);
        assertEquals("a diagonal neighbour alone is not connected", 1, vein.size());
    }

    @Test
    public void theRadiusBoundsTheFill() {
        // A solid 9x1x9 slab: radius 2 from the centre must stay inside it.
        VeinFlood.Matches slab = (x, y, z) -> y == 10 && Math.abs(x) <= 20 && Math.abs(z) <= 20;
        List<int[]> vein = VeinFlood.connected(0, 10, 0, 2, 500, slab);
        for (int[] point : vein) {
            long distanceSq = (long) point[0] * point[0] + (long) point[2] * point[2];
            assertTrue("outside radius: " + point[0] + "," + point[2], distanceSq <= 4);
        }
        assertTrue(vein.size() > 1);
    }

    @Test
    public void theBlockCapIsRespected() {
        VeinFlood.Matches slab = (x, y, z) -> y == 10;
        List<int[]> vein = VeinFlood.connected(0, 10, 0, 8, 7, slab);
        assertEquals(7, vein.size());
    }

    @Test
    public void nearestBlocksComeFirstSoACapLeavesTheFarVeinIntact() {
        VeinFlood.Matches line = (x, y, z) -> y == 10 && z == 0 && x > 0 && x <= 6;
        List<int[]> vein = VeinFlood.connected(0, 10, 0, 8, 3, line);
        assertEquals(3, vein.size());
        assertEquals(1, vein.get(1)[0]);
        assertEquals(2, vein.get(2)[0]);
    }

    @Test
    public void aSingleBlockTargetNeverScans() {
        List<int[]> vein = VeinFlood.connected(5, 5, 5, 4, 1, (x, y, z) -> true);
        assertEquals(1, vein.size());
        assertArrayEqualsInts(new int[]{5, 5, 5}, vein.get(0));
    }

    @Test
    public void limitsAreClampedIntoTheDocumentedRange() {
        assertEquals(VeinLimits.MIN_RADIUS, VeinLimits.clampRadius(-4));
        assertEquals(VeinLimits.MAX_RADIUS, VeinLimits.clampRadius(99));
        assertEquals(VeinLimits.MIN_BLOCKS, VeinLimits.clampBlocks(0));
        assertEquals(VeinLimits.MAX_BLOCKS, VeinLimits.clampBlocks(9_999));
        assertNotNull(VeinLimits.class);
    }

    private static void assertArrayEqualsInts(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i, expected[i], actual[i]);
        }
    }
}
