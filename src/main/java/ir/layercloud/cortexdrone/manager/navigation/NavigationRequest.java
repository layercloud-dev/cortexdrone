package ir.layercloud.cortexdrone.manager.navigation;

import ir.layercloud.cortexdrone.DroneController;

import lombok.Builder;
import lombok.Value;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * One goal request. The navigation manager chooses the route internally.
 */
@Value
@Builder
public class NavigationRequest {
    DroneController controller;
    EntityPlayerMP player;

    boolean coordinates;
    double x;
    double y;
    double z;

    boolean standoff;
    int standoffX;
    int standoffY;
    int standoffZ;

    /** Per-call flight-speed multiplier; 0 = use the configured value. */
    double speedMultiplier;

    /** Per-call recovery policy chosen by the model. */
    boolean allowDig;
    int maxDigBlocks;
    int recoveryAttempts;
}
