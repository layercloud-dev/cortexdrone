package ir.layercloud.cortexdrone;

import li.cil.oc.common.entity.Drone;

import java.util.UUID;

/**
 * Identity and entity link for one drone.
 *
 * <p>The controller is deliberately state-free beyond identity: navigation,
 * visuals, interaction, task/loop and harness session state live in their own
 * managers and are keyed by this controller. There is no mode flag and no work
 * queue.</p>
 */
public class DroneController {
    /**
     * Slots every AI drone is normalised to.
     */
    public static final int INVENTORY_SLOTS = 8;
    /**
     * Owner value for drones that belong to the server, not a player.
     */
    public static final String SERVER_OWNER = "Server";

    public final int id;
    public Drone drone;
    public final UUID entityUuid;
    public final String name;
    public String ownerName;

    public DroneController(int id, Drone drone, String name, String ownerName) {
        this.id = id;
        this.drone = drone;
        this.entityUuid = drone.getUniqueID();
        this.name = name;
        this.ownerName = ownerName;
    }

    /**
     * Identity-only controller for unit tests and command-argument resolution.
     *
     * <p>It has no entity link, so anything that needs the world must check
     * {@link #drone} first. Production code always uses the entity constructor.</p>
     */
    public static DroneController forTest(int id, String name, String ownerName) {
        return new DroneController(id, name, ownerName);
    }

    private DroneController(int id, String name, String ownerName) {
        this.id = id;
        this.drone = null;
        this.entityUuid = new UUID(0L, id);
        this.name = name;
        this.ownerName = ownerName;
    }
}
