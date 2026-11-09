package ir.layercloud.cortexdrone.harness.tool;

/**
 * Tool-facing alias of the world scan bounds.
 *
 * <p>The values are owned by
 * {@link ir.layercloud.cortexdrone.service.world.ScanLimits} so the service
 * layer does not depend on the harness. This class exists so tool schemas read
 * naturally; it declares no numbers of its own.</p>
 */
public final class ScanLimits {
    private ScanLimits() {
    }

    public static final int MIN_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_RADIUS;
    public static final int MAX_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_RADIUS;
    public static final int MIN_RESULTS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_RESULTS;
    public static final int MAX_RESULTS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_RESULTS;
    public static final int DEFAULT_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_RADIUS;
    public static final int DEFAULT_RESULTS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_RESULTS;
    public static final int MIN_ROOM_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_ROOM_RADIUS;
    public static final int MAX_ROOM_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_ROOM_RADIUS;
    public static final int DEFAULT_ROOM_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_ROOM_RADIUS;
    public static final int MIN_FURNACE_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_FURNACE_RADIUS;
    public static final int MAX_FURNACE_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_FURNACE_RADIUS;
    public static final int DEFAULT_FURNACE_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_FURNACE_RADIUS;
    public static final int MIN_VEIN_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_VEIN_RADIUS;
    public static final int MAX_VEIN_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_VEIN_RADIUS;
    public static final int DEFAULT_VEIN_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_VEIN_RADIUS;
    public static final int MIN_DISTANT_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_DISTANT_RADIUS;
    public static final int MAX_DISTANT_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_DISTANT_RADIUS;
    public static final int DEFAULT_DISTANT_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_DISTANT_RADIUS;
    public static final int MIN_ENTITY_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_ENTITY_RADIUS;
    public static final int MAX_ENTITY_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_ENTITY_RADIUS;
    public static final int DEFAULT_ENTITY_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_ENTITY_RADIUS;
    public static final int MIN_DROP_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MIN_DROP_RADIUS;
    public static final int MAX_DROP_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.MAX_DROP_RADIUS;
    public static final int DEFAULT_DROP_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.DEFAULT_DROP_RADIUS;
    public static final int TIME_SLICE_RADIUS =
            ir.layercloud.cortexdrone.service.world.ScanLimits.TIME_SLICE_RADIUS;

    public static int clampRadius(int radius) {
        return ir.layercloud.cortexdrone.service.world.ScanLimits.clampRadius(radius);
    }

    public static int clampResults(int limit) {
        return ir.layercloud.cortexdrone.service.world.ScanLimits.clampResults(limit);
    }
}
