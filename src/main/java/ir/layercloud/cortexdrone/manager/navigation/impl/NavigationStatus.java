package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Read-only snapshot of one drone's navigation session for status reporting.
 *
 * <p>Split out of {@link NavigationManager} so the manager stops mixing session
 * bookkeeping with presentation, and so the status shape can be tested without
 * a live session.</p>
 */
@Getter
@RequiredArgsConstructor
public final class NavigationStatus {
    private final boolean active;
    private final String phase;
    private final String reason;
    private final double goalX;
    private final double goalY;
    private final double goalZ;

    /**
     * The idle snapshot; every field describes "nothing is running".
     */
    public static NavigationStatus idle() {
        return new NavigationStatus(false, "idle", "", 0, 0, 0);
    }

    /**
     * Builds the snapshot for one controller from the live session state.
     */
    public static NavigationStatus of(NavigationSession session, NavigationState state) {
        if (session == null || state == null) return idle();
        return new NavigationStatus(true, session.phase.name().toLowerCase(), session.reason,
                state.goalX, state.goalY, state.goalZ);
    }

    /**
     * Convenience for callers that only have a controller.
     */
    public static NavigationStatus forDrone(boolean active, String phase, String reason,
                                            double goalX, double goalY, double goalZ) {
        return new NavigationStatus(active, phase, reason, goalX, goalY, goalZ);
    }

    /**
     * True when the drone is mid-flight rather than planning.
     */
    public boolean isFlying() {
        return this.active && "flight".equals(this.phase);
    }

    /**
     * Short human-readable line used by chat status and the tool envelope.
     */
    public String describe(DroneController controller) {
        if (!this.active) return "idle";
        String name = controller != null && controller.name != null ? controller.name : "drone";
        return name + " " + this.phase + (this.reason == null || this.reason.isEmpty()
                ? "" : " (" + this.reason + ")");
    }
}
