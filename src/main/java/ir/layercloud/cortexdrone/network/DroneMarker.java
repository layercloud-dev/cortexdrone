package ir.layercloud.cortexdrone.network;

import lombok.Getter;

/**
 * One drone position in the map-sync snapshot.
 *
 * <p>Immutable value object shared by the server sender, the network packet
 * and the client-side Xaero waypoint hook. It carries only the data Xaero
 * needs: a stable controller id, a display name, the owner, the dimension and
 * the exact world position.</p>
 */
@Getter
public final class DroneMarker {
    private final int id;
    private final String name;
    private final String owner;
    private final int dimension;
    private final double x;
    private final double y;
    private final double z;

    public DroneMarker(int id, String name, String owner, int dimension,
                       double x, double y, double z) {
        this.id = id;
        this.name = name != null ? name : "";
        this.owner = owner != null ? owner : "";
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

}
