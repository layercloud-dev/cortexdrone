package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Connected-vein clustering must account for every matched block.
 *
 * <p>Regression: blocks were marked visited when they were <em>enqueued</em>,
 * and the flood fill stopped at 256 cluster members. The queue still held
 * visited-but-unreported blocks, which the outer loop then skipped, so a large
 * vein silently lost blocks from both the cluster and the total.</p>
 */
public class VeinClusteringTest {
    private static final VeinClustering.PositionKey KEY = (x, y, z) -> pack(x, y, z);

    /** Distinct bit ranges: x[40..], z[20..], y[0..19]. No overlap. */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (z & 0xFFFFF) << 20) | (y & 0xFFFFFL);
    }

    /** Builds a solid cube of one block identity. */
    private static Map<Long, int[]> cube(int size, int id, int meta) {
        Map<Long, int[]> matched = new LinkedHashMap<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    matched.put(pack(x, y, z), new int[]{x, y, z, id, meta});
                }
            }
        }
        return matched;
    }

    @Test
    public void largeVeinKeepsAnExactTotalCount() {
        // 10^3 = 1000 blocks, far above the 256 sample cap.
        Map<Long, int[]> matched = cube(10, 15, 0);
        List<VeinClustering.Cluster> clusters = VeinClustering.cluster(matched, KEY);

        assertEquals("one solid cube is one vein", 1, clusters.size());
        VeinClustering.Cluster vein = clusters.get(0);
        assertEquals("the exact count must survive the sample cap", 1000, vein.totalCount());
        assertEquals(VeinClustering.MAX_BLOCKS_PER_VEIN, vein.blocks().size());
        assertTrue("the response must say it was truncated", vein.truncated());
    }

    @Test
    public void everyMatchedBlockIsAccountedForAcrossClusters() {
        // Two disjoint cubes of different ores.
        Map<Long, int[]> matched = new LinkedHashMap<>();
        matched.putAll(cube(4, 15, 0));
        Map<Long, int[]> second = new LinkedHashMap<>();
        for (int x = 100; x < 104; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    second.put(pack(x, y, z), new int[]{x, y, z, 16, 1});
                }
            }
        }
        matched.putAll(second);

        List<VeinClustering.Cluster> clusters = VeinClustering.cluster(matched, KEY);
        int total = 0;
        for (VeinClustering.Cluster cluster : clusters) total += cluster.totalCount();

        assertEquals("no block may vanish between clusters", matched.size(), total);
        assertEquals(2, clusters.size());
    }

    @Test
    public void differentOreMetadataIsNotConnected() {
        Map<Long, int[]> matched = new LinkedHashMap<>();
        matched.put(pack(0, 0, 0), new int[]{0, 0, 0, 15, 0});
        matched.put(pack(1, 0, 0), new int[]{1, 0, 0, 15, 1});

        List<VeinClustering.Cluster> clusters = VeinClustering.cluster(matched, KEY);
        assertEquals("touching blocks of different variants stay separate", 2, clusters.size());
    }

    @Test
    public void centroidIsTheMeanOfTheWholeVein() {
        Map<Long, int[]> matched = new LinkedHashMap<>();
        for (int x = 0; x <= 6; x++) {
            matched.put(pack(x, 0, 0), new int[]{x, 0, 0, 15, 0});
        }
        VeinClustering.Cluster vein = VeinClustering.cluster(matched, KEY).get(0);
        assertEquals(3, vein.center()[0]);
        assertEquals(0, vein.center()[1]);
        assertEquals(0, vein.center()[2]);
    }

    @Test
    public void emptyOrNullInputIsSafe() {
        assertTrue(VeinClustering.cluster(null, KEY).isEmpty());
        assertTrue(VeinClustering.cluster(new LinkedHashMap<>(), KEY).isEmpty());
    }
}
