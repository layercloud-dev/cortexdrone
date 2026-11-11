package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.List;

/**
 * Spawn, link and despawn lifecycle for AI drones.
 *
 * <p>Extracted from {@link AIDroneManager} so the manager stays a tick/event
 * coordinator. Every world mutation and every "forget this controller" call the
 * lifecycle needs is behind {@link Collaborators}, which keeps the lifecycle
 * unit-testable with fakes and makes the coupling explicit.</p>
 */
final class DroneLifecycle {
    /**
     * How far a player can be from a drone and still link it.
     */
    static final double LINK_RADIUS = 8.0;
    /**
     * Y offset used when the spawn point in front of the player is blocked.
     */
    private static final double FALLBACK_SPAWN_Y = 1.5D;
    /**
     * Y offset used for a normal spawn in front of the player.
     */
    private static final double SPAWN_Y = 1.25D;
    /**
     * Spawn offset in front of the player.
     */
    private static final double SPAWN_FORWARD = 2.0D;
    /**
     * LED colour of a freshly spawned drone.
     */
    private static final int SPAWN_LED = 0x00FFFF;
    /**
     * LED colour of a newly linked drone.
     */
    private static final int LINK_LED = 0x00FF88;
    /**
     * Initial flight-target acceleration for a spawned drone.
     */
    private static final float SPAWN_ACCELERATION = 0.1F;
    /**
     * Suffix appended to the owner's name for a default drone name.
     */
    static final String DEFAULT_NAME_SUFFIX = "-AI";

    /**
     * Everything the lifecycle touches outside the registry.
     */
    interface Collaborators {
        DroneRegistry registry();

        DroneLogger logger();

        ModConfig config();

        void saveConfig();

        boolean ensureMachineRunningState(Drone drone);

        /**
         * Releases navigation, visuals, chunks, sessions and breadcrumbs.
         */
        void forgetController(DroneController controller);

        /**
         * Drops the drone's inventory at the given point.
         */
        int dropInventory(Drone drone, World world, double x, double y, double z);
    }

    private final Collaborators collaborators;

    /**
     * Creates and indexes a controller for a live drone.
     */
    DroneController register(Drone drone, String name, String ownerName) {
        DroneRegistry registry = this.collaborators.registry();
        DroneController controller = new DroneController(registry.nextId(), drone, name, ownerName);
        registry.index(controller);
        return controller;
    }

    /**
     * Finds a controller by the entity it points at or by entity UUID.
     */
    DroneController findByEntity(Drone drone) {
        if (drone == null) return null;
        DroneController byInstance = this.collaborators.registry().findByControllerEntity(drone);
        if (byInstance != null) return byInstance;
        // Fall back to UUID: an entity may be reloaded as a new Java instance.
        java.util.UUID uuid = drone.getUniqueID();
        if (uuid == null) return null;
        for (DroneController controller : this.collaborators.registry().all()) {
            if (uuid.equals(controller.entityUuid)) {
                controller.drone = drone;
                return controller;
            }
        }
        return null;
    }

    DroneLifecycle(Collaborators collaborators) {
        this.collaborators = collaborators;
    }

    /**
     * Spawns a drone beside {@code player}, replacing any existing live drone.
     *
     * @param customName optional name; defaults to {@code <owner>-AI}
     */
    DroneController spawn(EntityPlayerMP player, String customName) {
        DroneRegistry registry = this.collaborators.registry();
        DroneController existing = registry.byOwner(player.getCommandSenderName());
        if (existing != null && existing.drone != null && !existing.drone.isDead) {
            despawn(existing, player);
        }

        World world = player.worldObj;
        Drone drone = new Drone(world);
        String requested = customName != null
                ? ir.layercloud.cortexdrone.service.text.TextSanitizer.chat(customName) : "";
        String name = !requested.isEmpty()
                ? requested
                : player.getCommandSenderName() + DEFAULT_NAME_SUFFIX;

        drone.setName(name);
        drone.ownerName_$eq(player.getCommandSenderName());
        drone.ownerUUID_$eq(player.getUniqueID());

        double[] position = spawnPosition(player);
        drone.setPosition(position[0], position[1], position[2]);
        drone.preparePowerUp();
        drone.inventorySize_$eq(DroneController.INVENTORY_SLOTS);
        drone.lightColor_$eq(SPAWN_LED);
        drone.statusText_$eq("Following\n" + DroneStatusRenderer.trimStatus(player.getCommandSenderName()));

        FlightPathfinder.setDroneFlightTarget(drone, position[0], position[1], position[2],
                SPAWN_ACCELERATION);
        this.collaborators.ensureMachineRunningState(drone);
        world.spawnEntityInWorld(drone);

        DroneController controller = register(drone, name, player.getCommandSenderName());
        this.collaborators.saveConfig();
        this.collaborators.logger().log("SPAWN", controller,
                "Spawned new AI drone for " + player.getCommandSenderName(),
                String.format("pos=(%.1f,%.1f,%.1f)", position[0], position[1], position[2]));
        return controller;
    }

    /**
     * Spawn point two blocks in front of the player, falling back to directly
     * above them when that cell is solid.
     */
    private static double[] spawnPosition(EntityPlayerMP player) {
        World world = player.worldObj;
        double yaw = Math.toRadians(player.rotationYaw);
        double x = player.posX - Math.sin(yaw) * SPAWN_FORWARD;
        double y = player.posY + SPAWN_Y;
        double z = player.posZ + Math.cos(yaw) * SPAWN_FORWARD;
        boolean blocked = !ItemClassifier.isPassableVoxel(world,
                MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z));
        if (blocked) {
            return new double[]{player.posX, player.posY + FALLBACK_SPAWN_Y, player.posZ};
        }
        return new double[]{x, y, z};
    }

    /**
     * Links the nearest drone within {@link #LINK_RADIUS} to {@code player}.
     *
     * @return the controller, or {@code null} when no drone is close enough
     */
    DroneController linkNearest(EntityPlayerMP player) {
        World world = player.worldObj;
        AxisAlignedBB box = player.boundingBox.expand(LINK_RADIUS, LINK_RADIUS, LINK_RADIUS);
        List<?> nearby = world.getEntitiesWithinAABB(Drone.class, box);
        Drone best = null;
        double bestDistance = Double.MAX_VALUE;
        if (nearby != null) {
            for (Object candidate : nearby) {
                if (!(candidate instanceof Drone)) continue;
                Drone drone = (Drone) candidate;
                if (drone.isDead) continue;
                double distance = drone.getDistanceToEntity(player);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = drone;
                }
            }
        }
        if (best == null) return null;

        DroneRegistry registry = this.collaborators.registry();
        DroneController controller = findByEntity(best);
        String playerName = player.getCommandSenderName();
        best.ownerName_$eq(playerName);
        best.ownerUUID_$eq(player.getUniqueID());
        this.collaborators.ensureMachineRunningState(best);
        best.inventorySize_$eq(DroneController.INVENTORY_SLOTS);
        best.lightColor_$eq(LINK_LED);
        best.statusText_$eq("Linked\n" + DroneStatusRenderer.trimStatus(playerName));

        if (controller == null) {
            String droneName = best.getCommandSenderName() != null
                    && !best.getCommandSenderName().isEmpty()
                    ? best.getCommandSenderName()
                    : playerName + DEFAULT_NAME_SUFFIX;
            controller = register(best, droneName, playerName);
        } else {
            registry.reassignOwner(controller, playerName);
        }
        this.collaborators.saveConfig();
        this.collaborators.logger().log("LINK", controller,
                "Linked drone to " + playerName, String.format("dist=%.2f", bestDistance));
        return controller;
    }

    /**
     * Despawns a drone, dropping its inventory at {@code dropTarget} when given.
     *
     * @return the number of dropped items
     */
    int despawn(DroneController controller, EntityPlayerMP dropTarget) {
        if (controller == null || controller.drone == null) return 0;
        Drone drone = controller.drone;
        World world = drone.worldObj;
        double x = dropTarget != null ? dropTarget.posX : drone.posX;
        double y = dropTarget != null ? dropTarget.posY + 0.5D : drone.posY;
        double z = dropTarget != null ? dropTarget.posZ : drone.posZ;

        int dropped = this.collaborators.dropInventory(drone, world, x, y, z);
        this.collaborators.forgetController(controller);
        this.collaborators.registry().markDespawned(
                drone.getUniqueID());
        removeSavedBinding(drone);

        drone.setDead();
        this.collaborators.registry().remove(controller);
        this.collaborators.saveConfig();
        this.collaborators.logger().log("DESPAWN", controller,
                "Despawned drone cleanly", "droppedItems=" + dropped);
        return dropped;
    }

    /**
     * Removes the persisted binding for a despawned entity.
     */
    private void removeSavedBinding(Drone drone) {
        ModConfig config = this.collaborators.config();
        if (config == null || config.getSavedDrones() == null) return;
        String uuid = drone.getUniqueID() != null ? drone.getUniqueID().toString() : null;
        if (uuid == null) return;
        config.getSavedDrones().removeIf(saved -> saved != null && uuid.equals(saved.getUuid()));
    }

}
