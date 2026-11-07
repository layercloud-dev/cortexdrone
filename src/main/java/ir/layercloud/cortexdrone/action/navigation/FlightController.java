package ir.layercloud.cortexdrone.action.navigation;

import ir.layercloud.cortexdrone.DroneController;

/**
 * Narrow movement port for actions that need to travel as part of their own
 * job (deposit, delivery, drop, mining standoffs) without holding the whole
 * navigation manager.
 *
 * <p>Implemented by {@code NavigationManager}; consumed by actions. This keeps
 * the action layer free of manager imports while navigation still owns the
 * per-drone path state.</p>
 */
public interface FlightController {
    /**
     * Runs one flight step toward {@code (x, y, z)} for a caller that does not
     * own a navigation session.
     */
    void flyToward(DroneController controller, double x, double y, double z, String label);
}
