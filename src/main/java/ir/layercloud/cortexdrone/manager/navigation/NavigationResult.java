package ir.layercloud.cortexdrone.manager.navigation;

import lombok.Builder;
import lombok.Value;

/**
 * Immediate acknowledgement of a navigation goal request.
 */
@Value
@Builder
public class NavigationResult {
    boolean ok;
    String error;
    boolean started;
    boolean arrived;
    double goalX;
    double goalY;
    double goalZ;
    String phase;
    /**
     * Monotonic token for this request; waiters use it to read the outcome.
     */
    long requestId;

    public static NavigationResult failure(String error) {
        return builder().ok(false).error(error).build();
    }

    public static NavigationResult started(double x, double y, double z, String phase, long requestId) {
        return builder().ok(true).started(true).goalX(x).goalY(y).goalZ(z)
                .phase(phase).requestId(requestId).build();
    }

    public static NavigationResult stopped(double x, double y, double z) {
        return builder().ok(true).arrived(true).goalX(x).goalY(y).goalZ(z).phase("stopped").build();
    }
}
