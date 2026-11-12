package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;

/**
 * Resolves the flight-speed multiplier for one drone.
 *
 * <p>Extracted from {@link NavigationManager} to keep that class inside its
 * modular line budget. The rules are simple but easy to get subtly wrong:</p>
 *
 * <ul>
 *   <li>a per-call request wins over the configured value;</li>
 *   <li>an absent request falls back to the config;</li>
 *   <li>a carried rider always resolves to normal speed, even when the rider
 *       boarded after the request started.</li>
 * </ul>
 */
final class NavigationSpeed {
    private NavigationSpeed() {
    }

    static double of(NavigationSession session, DroneController controller, ModConfig config) {
        // Check the live rider as well as the session flag: a player who boards
        // after the request started must not be flown at a boosted cap.
        boolean carrying = RiderGuard.isCarrying(controller != null ? controller.drone : null)
                || (session != null && session.carryingRider);
        return DroneSpeed.effective(DroneSpeed.configured(config),
                session != null ? session.speedMultiplier : 0.0, carrying);
    }

    /**
     * Multiplier stored on a new session: request first, then config.
     */
    static double requested(NavigationRequest request,
                            ModConfig config) {
        return DroneSpeed.clampMultiplier(request.getSpeedMultiplier() > 0.0
                ? request.getSpeedMultiplier() : DroneSpeed.configured(config));
    }

    /**
     * Multiplier exposed on the navigation state for the leg planner. A rider
     * always plans at normal speed so the arrival tolerance matches the cap.
     */
    static double capped(NavigationSession session) {
        return session != null && session.carryingRider ? 1.0 : session != null
                ? session.speedMultiplier : 1.0;
    }
}
