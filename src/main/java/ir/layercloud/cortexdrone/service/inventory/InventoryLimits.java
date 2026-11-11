package ir.layercloud.cortexdrone.service.inventory;

/**
 * Radius and result bounds for read-only inventory tools.
 *
 * <p>Lives in the service layer because the service is what clamps; the tool
 * schema aliases these values. Previously each handler inlined its own
 * {@code Math.min(64, Math.max(4, ...))} and they disagreed (28, 32, 36).</p>
 */
public final class InventoryLimits {
    private InventoryLimits() {
    }

    /**
     * Smallest accepted search radius.
     */
    public static final int MIN_RADIUS = 4;
    /**
     * Largest accepted search radius for item/container tools.
     */
    public static final int MAX_RADIUS = 64;
    /**
     * Largest accepted radius for a furnace scan; the scan policy owns it.
     */
    public static final int MAX_FURNACE_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_FURNACE_RADIUS;
    /**
     * Default search radius.
     */
    public static final int DEFAULT_RADIUS = 32;
    /**
     * Default container search radius.
     */
    public static final int DEFAULT_CHEST_RADIUS = 28;
    /**
     * Largest number of results a query will return.
     */
    public static final int MAX_RESULTS = 64;
    /**
     * Smallest pickup radius for a drop vacuum.
     */
    public static final int MIN_SUCK_RADIUS = 1;
    /**
     * Largest pickup radius for a drop vacuum.
     */
    public static final int MAX_SUCK_RADIUS = 16;
    /**
     * Pickup radius when the caller names none.
     */
    public static final double DEFAULT_SUCK_RADIUS = 6.5;

    /**
     * Largest item count a transfer may move in one call (36 stacks).
     */
    public static final int MAX_TRANSFER_COUNT = 2304;
    /**
     * Item count a transfer moves when the caller names none.
     */
    public static final int DEFAULT_TRANSFER_COUNT = 512;

    /**
     * Clamps a requested radius into the supported range.
     */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /**
     * Clamps a drop-vacuum radius into the supported range.
     */
    public static double clampSuckRadius(double radius) {
        return Math.max(MIN_SUCK_RADIUS, Math.min(MAX_SUCK_RADIUS, radius));
    }
}
