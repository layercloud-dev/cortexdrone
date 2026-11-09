package ir.layercloud.cortexdrone.service.world;

import java.util.Locale;

/**
 * Roof geometry for a generated structure plan.
 *
 * <p>Pure placement maths, extracted from {@link StructureArchitect}. A roof is
 * the part most likely to leave holes, so it is separated to be unit-testable
 * and to keep the builder an orchestrator.</p>
 */
final class RoofBuilder {
    /** Names that mean a gable/peak roof. */
    private static final String[] GABLE = {"gable", "pitched", "peak"};
    /** Names that mean a hip/pyramid roof. */
    private static final String[] HIP = {"hip", "pyramid"};

    private RoofBuilder() {
    }

    /**
     * Places the roof for {@code style}.
     *
     * <p>Gable roofs use {@code span / 2 + 1} layers so the last layer collapses
     * to a ridge row. The previous {@code (span + 1) / 2} stopped one layer
     * short on an odd span and left a full-length one-block hole.</p>
     */
    static void build(StructurePlan plan, int x1, int z1, int x2, int z2,
                      int roofBaseY, String roofStyle, String roofBlock) {
        String style = normalize(roofStyle);
        if (matches(style, GABLE)) {
            buildGable(plan, x1, z1, x2, z2, roofBaseY, roofBlock);
            return;
        }
        if (matches(style, HIP)) {
            buildHip(plan, x1, z1, x2, z2, roofBaseY, roofBlock);
            return;
        }
        // Flat roof: the whole footprint at roofBaseY.
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                plan.addTask(x, roofBaseY, z, roofBlock, -1, true,
                        StructurePlan.Phase.ROOF);
            }
        }
    }

    /** Number of vertical layers {@link #build} will place. */
    static int layerCount(int width, int depth, String roofStyle) {
        String style = normalize(roofStyle);
        if (matches(style, GABLE)) {
            int span = Math.max(width, depth) - 1;
            return Math.max(1, span / 2 + 1);
        }
        if (matches(style, HIP)) {
            return Math.max(1, Math.min(width, depth) / 2 + 1);
        }
        return 1;
    }

    private static void buildGable(StructurePlan plan, int x1, int z1,
                                   int x2, int z2, int roofBaseY, String block) {
        boolean alongX = (x2 - x1) >= (z2 - z1);
        int span = alongX ? (z2 - z1) : (x2 - x1);
        int start = alongX ? z1 : x1;
        int end = alongX ? z2 : x2;
        int layers = span / 2 + 1;
        for (int layer = 0; layer < layers; layer++) {
            int y = roofBaseY + layer;
            int low = start + layer;
            int high = end - layer;
            if (low > high) break;
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    boolean edge = alongX ? (z == low || z == high) : (x == low || x == high);
                    boolean ridge = low == high;
                    if (edge || ridge) {
                        plan.addTask(x, y, z, block, -1, true, StructurePlan.Phase.ROOF);
                    }
                }
            }
        }
    }

    private static void buildHip(StructurePlan plan, int x1, int z1,
                                 int x2, int z2, int roofBaseY, String block) {
        int layers = Math.min(x2 - x1, z2 - z1) / 2 + 1;
        for (int layer = 0; layer < layers; layer++) {
            int y = roofBaseY + layer;
            int rx1 = x1 + layer;
            int rx2 = x2 - layer;
            int rz1 = z1 + layer;
            int rz2 = z2 - layer;
            if (rx1 > rx2 || rz1 > rz2) break;
            for (int x = rx1; x <= rx2; x++) {
                for (int z = rz1; z <= rz2; z++) {
                    boolean edge = x == rx1 || x == rx2 || z == rz1 || z == rz2;
                    boolean cap = rx1 == rx2 || rz1 == rz2;
                    if (edge || cap) {
                        plan.addTask(x, y, z, block, -1, true, StructurePlan.Phase.ROOF);
                    }
                }
            }
        }
    }

    private static String normalize(String roofStyle) {
        return roofStyle == null ? "flat" : roofStyle.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String style, String[] names) {
        for (String name : names) {
            if (name.equals(style)) return true;
        }
        return false;
    }
}
