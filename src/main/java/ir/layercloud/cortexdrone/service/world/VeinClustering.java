package ir.layercloud.cortexdrone.service.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 26-connected clustering of matched block positions.
 *
 * <p>Pure graph work with no world access, so the connection rules can be
 * unit-tested directly. The caller supplies the packed-key function, which keeps
 * this class independent of the navigation pathfinder's packing layout.</p>
 */
public final class VeinClustering {
    /** Maximum block coordinates a single vein response carries. */
    public static final int MAX_BLOCKS_PER_VEIN = 256;

    private VeinClustering() {
    }

    /** Packs a block position into the key used by the matched map. */
    public interface PositionKey {
        long key(int x, int y, int z);
    }

    /** One connected component: its full size plus a bounded coordinate sample. */
    public static final class Cluster {
        private final List<int[]> blocks;
        private final int totalCount;
        private final int[] center;

        Cluster(List<int[]> blocks, int totalCount, int[] center) {
            this.blocks = blocks;
            this.totalCount = totalCount;
            this.center = center;
        }

        /** Coordinate sample, at most {@link #MAX_BLOCKS_PER_VEIN} entries. */
        public List<int[]> blocks() {
            return this.blocks;
        }

        /** Exact number of connected blocks, even when the sample is capped. */
        public int totalCount() {
            return this.totalCount;
        }

        /** Centroid of the whole component. */
        public int[] center() {
            return this.center;
        }

        /** True when {@link #blocks()} is shorter than {@link #totalCount()}. */
        public boolean truncated() {
            return this.blocks.size() < this.totalCount;
        }
    }

    /**
     * Groups every matched position into 26-connected components.
     *
     * <p>Each component is drained completely before the next one starts, so a
     * block can never be dropped between clusters. The returned sample is
     * capped, but {@link Cluster#totalCount()} stays exact.</p>
     */
    public static List<Cluster> cluster(Map<Long, int[]> matched, PositionKey keys) {
        return cluster(matched, keys, MAX_BLOCKS_PER_VEIN);
    }

    public static List<Cluster> cluster(Map<Long, int[]> matched, PositionKey keys, int maxBlocksPerVein) {
        List<Cluster> clusters = new ArrayList<>();
        if (matched == null || matched.isEmpty() || keys == null) return clusters;
        int cap = Math.max(1, maxBlocksPerVein);
        Set<Long> visited = new HashSet<>();

        for (Map.Entry<Long, int[]> entry : matched.entrySet()) {
            if (visited.contains(entry.getKey())) continue;

            List<int[]> sample = new ArrayList<>();
            Deque<int[]> queue = new ArrayDeque<>();
            queue.add(entry.getValue());
            int total = 0;
            double sumX = 0, sumY = 0, sumZ = 0;
            int identityId = entry.getValue()[3];
            int identityMeta = entry.getValue()[4];

            while (!queue.isEmpty()) {
                int[] current = queue.pollFirst();
                long currentKey = keys.key(current[0], current[1], current[2]);
                if (!visited.add(currentKey)) continue;
                total++;
                sumX += current[0];
                sumY += current[1];
                sumZ += current[2];
                if (sample.size() < cap) sample.add(current);

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            int nx = current[0] + dx;
                            int ny = current[1] + dy;
                            int nz = current[2] + dz;
                            long neighborKey = keys.key(nx, ny, nz);
                            if (visited.contains(neighborKey)) continue;
                            int[] neighbor = matched.get(neighborKey);
                            if (neighbor == null) continue;
                            if (neighbor[3] != identityId || neighbor[4] != identityMeta) continue;
                            queue.addLast(neighbor);
                        }
                    }
                }
            }

            if (total == 0) continue;
            int[] center = new int[]{
                    (int) Math.round(sumX / total),
                    (int) Math.round(sumY / total),
                    (int) Math.round(sumZ / total)
            };
            clusters.add(new Cluster(sample, total, center));
        }
        return clusters;
    }
}
