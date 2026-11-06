package ir.layercloud.cortexdrone.harness.session;

import ir.layercloud.cortexdrone.drone.DroneController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link DroneSession} per controller.
 *
 * <p>All harness-side per-drone state lives here instead of on the controller.
 * Sessions are dropped when the controller is unregistered.</p>
 */
public final class SessionManager {
    private final Map<DroneController, DroneSession> sessions = new ConcurrentHashMap<>();

    public DroneSession session(DroneController controller) {
        if (controller == null) return null;
        return this.sessions.computeIfAbsent(controller, ignored -> new DroneSession());
    }

    public void forget(DroneController controller) {
        if (controller != null) this.sessions.remove(controller);
    }
}
