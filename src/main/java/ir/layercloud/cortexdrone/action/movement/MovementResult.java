package ir.layercloud.cortexdrone.action.movement;

import java.util.Collections;
import java.util.List;

/**
 * Typed result of a movement executor. Serialization belongs to the RPC
 * adapter, never to the executor.
 */
public final class MovementResult {
    private final boolean ok;
    private final String error;
    private final String route;
    private final double goalX;
    private final double goalY;
    private final double goalZ;
    private final boolean viaSurface;
    private final List<int[]> breadcrumbs;
    private final int[] lastSurfaceAnchor;
    private final int breadcrumbCount;

    private MovementResult(boolean ok, String error, String route,
                           double goalX, double goalY, double goalZ, boolean viaSurface,
                           List<int[]> breadcrumbs, int[] lastSurfaceAnchor, int breadcrumbCount) {
        this.ok = ok;
        this.error = error;
        this.route = route;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.viaSurface = viaSurface;
        this.breadcrumbs = breadcrumbs != null
            ? Collections.unmodifiableList(breadcrumbs) : null;
        this.lastSurfaceAnchor = lastSurfaceAnchor;
        this.breadcrumbCount = breadcrumbCount;
    }

    public static MovementResult failure(String error) {
        return new MovementResult(false, error, null, 0, 0, 0, false, null, null, 0);
    }

    public static MovementResult started(String route, double gx, double gy, double gz,
                                         boolean viaSurface) {
        return new MovementResult(true, null, route, gx, gy, gz, viaSurface, null, null, 0);
    }

    public static MovementResult breadcrumbs(List<int[]> breadcrumbs, int[] lastSurfaceAnchor,
                                             int breadcrumbCount) {
        return new MovementResult(true, null, null, 0, 0, 0, false,
            breadcrumbs, lastSurfaceAnchor, breadcrumbCount);
    }

    public boolean isOk() { return this.ok; }
    public String error() { return this.error; }
    public String route() { return this.route; }
    public double goalX() { return this.goalX; }
    public double goalY() { return this.goalY; }
    public double goalZ() { return this.goalZ; }
    public boolean viaSurface() { return this.viaSurface; }
    public List<int[]> breadcrumbs() { return this.breadcrumbs; }
    public int[] lastSurfaceAnchor() { return this.lastSurfaceAnchor; }
    public int breadcrumbCount() { return this.breadcrumbCount; }
    public boolean hasBreadcrumbs() { return this.breadcrumbs != null; }
}
