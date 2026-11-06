package ir.layercloud.cortexdrone.drone;

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
}
