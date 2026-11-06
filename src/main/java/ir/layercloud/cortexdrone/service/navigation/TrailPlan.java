package ir.layercloud.cortexdrone.service.navigation;

import java.util.List;
import lombok.Value;

/** Read-only suggestion for following a recorded trail. */
@Value
public class TrailPlan {
    List<Breadcrumb> waypoints;
    Breadcrumb endpoint;
    String reason;

    public boolean isUseful() {
        return this.waypoints != null && !this.waypoints.isEmpty() && this.endpoint != null;
    }
}
