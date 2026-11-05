package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Typed input for movement actions. The RPC adapter is the only place that
 * converts JSON into this object.
 */
public final class MovementRequest {
    private final DroneController controller;
    private final EntityPlayerMP player;
    private final boolean hasCoordinates;
    private final double x;
    private final double y;
    private final double z;
    private final boolean hasStandoff;
    private final int standoffX;
    private final int standoffY;
    private final int standoffZ;
    private final String route;
    private final int depth;
    private final int limit;

    public MovementRequest(DroneController controller, EntityPlayerMP player,
                           boolean hasCoordinates, double x, double y, double z,
                           boolean hasStandoff, int standoffX, int standoffY, int standoffZ,
                           String route, int depth, int limit) {
        this.controller = controller;
        this.player = player;
        this.hasCoordinates = hasCoordinates;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hasStandoff = hasStandoff;
        this.standoffX = standoffX;
        this.standoffY = standoffY;
        this.standoffZ = standoffZ;
        this.route = route;
        this.depth = depth;
        this.limit = limit;
    }

    public DroneController controller() { return this.controller; }
    public EntityPlayerMP player() { return this.player; }
    public boolean hasCoordinates() { return this.hasCoordinates; }
    public double x() { return this.x; }
    public double y() { return this.y; }
    public double z() { return this.z; }
    public boolean hasStandoff() { return this.hasStandoff; }
    public int standoffX() { return this.standoffX; }
    public int standoffY() { return this.standoffY; }
    public int standoffZ() { return this.standoffZ; }
    public String route() { return this.route; }
    public int depth() { return this.depth; }
    public int limit() { return this.limit; }

    public boolean hasTarget() {
        return this.hasCoordinates || this.hasStandoff;
    }
}
