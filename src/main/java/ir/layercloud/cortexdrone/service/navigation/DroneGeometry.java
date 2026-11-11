package ir.layercloud.cortexdrone.service.navigation;

/**
 * Geometry constants shared by every navigation path.
 *
 * <p>A drone hovers a fixed offset above the block cell it targets, and the
 * horizontal centre is the middle of the cell. Both numbers appeared as bare
 * literals across the pathfinder, the breadcrumb service, the target resolver
 * and the manager; they must agree or the drone aims at a point no route
 * produces.</p>
 */
public final class DroneGeometry {
    private DroneGeometry() {
    }

    /**
     * Horizontal centre of a block cell.
     */
    public static final double CELL_CENTER = 0.5D;

    /**
     * Hover height above a path cell.
     *
     * <p>The drone's eye/aim point sits 0.65 above the cell floor. See
     * {@code RiderGuard}: a 1.8-block passenger needs headroom above this.</p>
     */
    public static final double HOVER_OFFSET = 0.65D;

    /**
     * Aim height for a standoff/entity target inside a block.
     *
     * <p>Deliberately different from {@link #HOVER_OFFSET}: the drone hovers
     * {@code 0.65} above a path cell, but a block or entity it is reaching for is
     * aimed at {@code 0.35} above its floor so the ray enters the body, not the
     * ceiling of the cell.</p>
     */
    public static final double AIM_OFFSET = 0.35D;

    /**
     * The aim point above block {@code (x, y, z)}.
     */
    public static double aimX(int x) {
        return x + CELL_CENTER;
    }

    public static double aimY(int y) {
        return y + AIM_OFFSET;
    }

    public static double aimZ(int z) {
        return z + CELL_CENTER;
    }
}
