package ir.layercloud.cortexdrone.service.world;

/**
 * Bounds for {@code break_block} vein mode.
 *
 * <p>Vein mining expands one break into the connected blocks of the same type.
 * Both the search radius and the number of candidates are deliberately small:
 * the whole set is inspected and broken on the server thread inside one tool
 * call, and a player asking for "this vein" never means a 30-block sphere.</p>
 */
public final class VeinLimits {
    /**
     * Smallest accepted search radius (blocks, Euclidean from the target).
     */
    public static final int MIN_RADIUS = 1;
    /**
     * Default search radius.
     */
    public static final int DEFAULT_RADIUS = 2;
    /**
     * Largest accepted search radius. At 8 the sphere holds ~2100 voxels; the
     * candidate cap is what actually bounds the work.
     */
    public static final int MAX_RADIUS = 8;

    /**
     * Smallest accepted candidate cap.
     */
    public static final int MIN_BLOCKS = 1;
    /**
     * Default candidate cap. A large ore vein is usually under 32 blocks.
     */
    public static final int DEFAULT_BLOCKS = 64;
    /**
     * Largest accepted candidate cap.
     */
    public static final int MAX_BLOCKS = 256;

    private VeinLimits() {
    }

    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    public static int clampBlocks(int blocks) {
        return Math.max(MIN_BLOCKS, Math.min(MAX_BLOCKS, blocks));
    }
}
