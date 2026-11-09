package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.ModConfig;
import li.cil.oc.common.entity.Drone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Re-adopts OpenComputers drones that exist in the world but have no controller.
 *
 * <p>Extracted from {@link AIDroneManager}: reconnection mixes config matching,
 * duplicate merging, naming and visual restore, which is a lifecycle concern of
 * its own. Keeping it here makes the merge rule (one active drone per owner)
 * readable in isolation.</p>
 */
final class DroneReconnector {
    /** Everything the reconnector needs from the manager. */
    interface Host {
        DroneController findControllerByEntity(Drone drone);

        DroneController getPlayerDrone(String playerName);

        DroneController registerDroneController(Drone drone, String name, String ownerName);

        boolean wasDespawned(Drone drone);

        boolean ensureMachineRunningState(Drone drone);

        ModConfig config();

        ir.layercloud.cortexdrone.config.DroneLogger logger();

        ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager visualsManager();

        /** Applies the saved aura/LED state and restores the aura action. */
        void restoreVisuals(DroneController controller, boolean lightAura, Integer ledColor);

        /** Moves items from a duplicate drone into the surviving one. */
        int mergeInventory(Drone from, Drone to);
    }

    /** Name used when the drone exposes none. */
    private static final String DEFAULT_NAME = "AI-Drone";

    private final Host host;

    DroneReconnector(Host host) {
        this.host = host;
    }

    void reconnectAll() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) return;
        for (WorldServer world : server.worldServers) {
            if (world == null || world.loadedEntityList == null) continue;
            List<?> entities = new ArrayList<Object>((Collection<?>) world.loadedEntityList);
            for (Object candidate : entities) {
                if (candidate instanceof Drone) reconnect((Drone) candidate);
            }
        }
    }

    private void reconnect(Drone drone) {
        if (drone.isDead) return;
        if (this.host.findControllerByEntity(drone) != null) return;
        if (this.host.wasDespawned(drone)) return;

        UUID uuid = drone.getUniqueID();
        String owner = drone.ownerName() != null && !drone.ownerName().isEmpty()
                ? drone.ownerName() : DroneController.SERVER_OWNER;
        String name = drone.getCommandSenderName() != null
                ? drone.getCommandSenderName() : DEFAULT_NAME;
        boolean restoredAura = false;
        Integer restoredLed = null;

        ModConfig.SavedDroneInfo saved = matchSaved(uuid, owner);
        if (saved != null) {
            if (saved.getName() != null && !saved.getName().isEmpty()) name = saved.getName();
            if (saved.getOwner() != null && !saved.getOwner().isEmpty()) owner = saved.getOwner();
            restoredAura = saved.isLightAura();
            restoredLed = saved.getLedColor();
        }

        // One active drone per owner: merge a stray duplicate instead of
        // leaving two controllers fighting over the same player's commands.
        if (!DroneController.SERVER_OWNER.equalsIgnoreCase(owner)) {
            DroneController existing = this.host.getPlayerDrone(owner);
            if (existing != null && existing.drone != null
                    && !existing.drone.isDead && existing.drone != drone) {
                this.host.mergeInventory(drone, existing.drone);
                drone.setDead();
                this.host.logger().log("LINK", existing,
                        "Merged and despawned duplicate world drone for " + owner, "");
                return;
            }
        }

        drone.setName(name);
        this.host.ensureMachineRunningState(drone);
        drone.inventorySize_$eq(DroneController.INVENTORY_SLOTS);
        DroneController controller = this.host.registerDroneController(drone, name, owner);
        this.host.restoreVisuals(controller, restoredAura, restoredLed);
        this.host.logger().log("LINK", controller, "Auto-reconnected world drone",
                String.format("owner=%s pos=(%.1f,%.1f,%.1f)",
                        owner, drone.posX, drone.posY, drone.posZ));
    }

    /** Finds the saved binding by UUID first, then by owner. */
    private ModConfig.SavedDroneInfo matchSaved(UUID uuid, String owner) {
        List<ModConfig.SavedDroneInfo> saved = this.host.config().getSavedDrones();
        if (saved == null) return null;
        for (ModConfig.SavedDroneInfo info : saved) {
            if (uuid != null && uuid.toString().equals(info.getUuid())) return info;
        }
        if (DroneController.SERVER_OWNER.equalsIgnoreCase(owner)) return null;
        for (ModConfig.SavedDroneInfo info : saved) {
            if (owner.equalsIgnoreCase(info.getOwner())) return info;
        }
        return null;
    }
}
