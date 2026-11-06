package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.model.DroneController;
import lombok.Builder;
import lombok.Value;
import net.minecraft.entity.player.EntityPlayerMP;

/** Immutable request data for one movement action. */
@Value
@Builder
public class MovementRequest {
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

    String route;
    int depth;
    int limit;

    public boolean hasTarget() {
        return this.coordinates || this.standoff;
    }
}
