package ir.layercloud.cortexdrone.service.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded flood fill over blocks of one kind, used by vein mining.
 *
 * <p>Vein mining is a connectivity question, not a radius scan: copper ore in a
 * diorite pocket is the copper that touches the copper you hit, not every copper
 * block that happens to be nearby. The fill therefore walks outward through
 * face-adjacent candidates only, and stops at {@code maxBlocks}.</p>
 *
 * <p>Pure and World-free so it can be unit tested: the caller passes a predicate
 * that answers "is this voxel the same block kind?" and supplies the world
 * bounds. Coordinates are packed into a single {@code long} key, so a 256-block
 * set needs no boxing.</p>
 *
 * <p>This is deliberately separate from {@link VeinScanner}, which is the
 * 26-connected ore scanner behind {@code scan_veins}: that one reports every
 * vein in a radius, this one expands exactly one break.</p>
 */
public final class VeinFlood {
    /**
     * Face neighbours, no diagonals: a diagonal block is not part of the same
     * connected vein for a single break.
     */
    private static final int[][] FACES = new int[][]{
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private VeinFlood() {
    }

    /**
     * Predicate for one candidate voxel.
     */
    public interface Matches {
        boolean test(int x, int y, int z);
    }

    /**
     * Connected coordinates of the same block kind, starting at the origin.
     *
     * <p>The origin is always included (the caller has already checked it).
     * Remaining results are ordered by distance from the origin, nearest first,
     * so a capped vein leaves the far blocks untouched instead of digging holes
     * around the drone.</p>
     *
     * @param radius    Euclidean bound from the origin, already clamped
     * @param maxBlocks hard cap on returned coordinates, already clamped
     */
    public static List<int[]> connected(int originX, int originY, int originZ,
                                        int radius, int maxBlocks, Matches matches) {
        List<int[]> out = new ArrayList<>();
        int cap = Math.max(1, maxBlocks);
        out.add(new int[]{originX, originY, originZ});
        if (cap == 1 || matches == null) return out;

        long radiusSq = (long) radius * radius;
        Set<Long> seen = new HashSet<>();
        seen.add(key(originX, originY, originZ));
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{originX, originY, originZ});
        List<int[]> found = new ArrayList<>();

        while (!queue.isEmpty() && out.size() + found.size() < cap) {
            int[] current = queue.removeFirst();
            for (int[] face : FACES) {
                int x = current[0] + face[0];
                int y = current[1] + face[1];
                int z = current[2] + face[2];
                long packed = key(x, y, z);
                if (!seen.add(packed)) continue;
                if (!WorldBounds.inWorld(y)) continue;
                long dx = x - originX;
                long dy = y - originY;
                long dz = z - originZ;
                if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                if (!matches.test(x, y, z)) continue;
                found.add(new int[]{x, y, z});
                queue.addLast(new int[]{x, y, z});
                if (out.size() + found.size() >= cap) break;
            }
        }

        found.sort((a, b) -> Long.compare(distanceSq(a, originX, originY, originZ),
                distanceSq(b, originX, originY, originZ)));
        out.addAll(found);
        return out;
    }

    private static long distanceSq(int[] point, int x, int y, int z) {
        long dx = point[0] - x;
        long dy = point[1] - y;
        long dz = point[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFF);
    }
}
