package ir.layercloud.cortexdrone.action.movement;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Immutable result of one movement action. */
@Value
@Builder
public class MovementResult {
    boolean ok;
    String error;
    String route;
    double goalX;
    double goalY;
    double goalZ;
    boolean viaSurface;
    List<int[]> breadcrumbs;
    int[] lastSurfaceAnchor;
    int breadcrumbCount;

    public boolean hasBreadcrumbs() {
        return this.breadcrumbs != null;
    }

    public static MovementResult failure(String error) {
        return builder().ok(false).error(error).build();
    }

    public static MovementResult started(String route, double goalX, double goalY, double goalZ,
                                         boolean viaSurface) {
        return builder()
            .ok(true)
            .route(route)
            .goalX(goalX)
            .goalY(goalY)
            .goalZ(goalZ)
            .viaSurface(viaSurface)
            .build();
    }

    public static MovementResult breadcrumbs(List<int[]> breadcrumbs, int[] lastSurfaceAnchor,
                                             int breadcrumbCount) {
        return builder()
            .ok(true)
            .breadcrumbs(breadcrumbs)
            .lastSurfaceAnchor(lastSurfaceAnchor)
            .breadcrumbCount(breadcrumbCount)
            .build();
    }
}
