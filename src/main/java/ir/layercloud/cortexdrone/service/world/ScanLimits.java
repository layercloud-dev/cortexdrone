package ir.layercloud.cortexdrone.service.world;

/**
 * Radius and result bounds for world scans.
 *
 * <p>Owned by the service layer because the service clamps; the tool schema
 * aliases these values. Previously each handler inlined its own defaults and
 * they disagreed with the schema, so a caller could not tell a cap from a
 * request.</p>
 */
public final class ScanLimits {
    private ScanLimits() {
    }

    /**
     * Smallest radius a generic block scan accepts (schema and clamp use 1).
     */
    public static final int MIN_RADIUS = 1;
    /**
     * Largest radius a block scan accepts, in blocks.
     */
    public static final int MAX_RADIUS = 64;
    /**
     * Smallest radius a build-site search accepts.
     */
    public static final int MIN_BUILD_SITE_RADIUS = 4;
    /**
     * Smallest number of block results a scan returns.
     */
    public static final int MIN_RESULTS = 1;
    /**
     * Largest number of block results a scan returns.
     */
    public static final int MAX_RESULTS = 2000;
    /**
     * Default radius when the caller names none.
     */
    public static final int DEFAULT_RADIUS = 24;
    /**
     * Default result limit when the caller names none.
     */
    public static final int DEFAULT_RESULTS = 400;

    /**
     * Smallest and largest radius a room scan accepts.
     */
    public static final int MIN_ROOM_RADIUS = 4;
    public static final int MAX_ROOM_RADIUS = 24;
    /**
     * Default radius for a room scan.
     */
    public static final int DEFAULT_ROOM_RADIUS = 18;

    /**
     * Radius bounds for a furnace scan.
     */
    public static final int MIN_FURNACE_RADIUS = 4;
    public static final int MAX_FURNACE_RADIUS = 36;
    /**
     * Default radius for a furnace scan.
     */
    public static final int DEFAULT_FURNACE_RADIUS = 24;

    /**
     * Smallest and largest radius a connected-vein scan accepts.
     */
    public static final int MIN_VEIN_RADIUS = 4;
    public static final int MAX_VEIN_RADIUS = 48;
    /**
     * Default connected-vein radius.
     */
    public static final int DEFAULT_VEIN_RADIUS = 28;

    /**
     * Smallest and largest radius a distant search accepts.
     */
    public static final int MIN_DISTANT_RADIUS = 64;
    public static final int MAX_DISTANT_RADIUS = 1500;
    /**
     * Default distant-search radius.
     */
    public static final int DEFAULT_DISTANT_RADIUS = 1200;

    /**
     * Entity search radius bounds and default.
     */
    public static final int MIN_ENTITY_RADIUS = 4;
    public static final int MAX_ENTITY_RADIUS = 180;
    public static final int DEFAULT_ENTITY_RADIUS = 64;

    /**
     * Dropped-item search radius bounds and default.
     */
    public static final int MIN_DROP_RADIUS = 2;
    public static final int MAX_DROP_RADIUS = 128;
    public static final int DEFAULT_DROP_RADIUS = 32;

    /**
     * Radius above which a scan must run as a pollable job.
     */
    public static final int TIME_SLICE_RADIUS = 32;

    /**
     * Largest air volume a room flood-fill will record.
     */
    public static final int MAX_ROOM_VOXELS = 4500;
    /**
     * Radius probed for an opening when the start voxel is solid.
     */
    public static final int ROOM_PROBE_RADIUS = 3;

    /**
     * Clamps a requested radius into the supported range.
     */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /**
     * Clamps a requested result limit into the supported range.
     */
    public static int clampResults(int limit) {
        return Math.max(MIN_RESULTS, Math.min(MAX_RESULTS, limit));
    }
}
