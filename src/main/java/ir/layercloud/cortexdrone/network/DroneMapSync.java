package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import li.cil.oc.common.entity.Drone;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends the live CortexDrone positions to every connected client.
 *
 * <p>Players without CortexDrone simply ignore the FML channel. Players with
 * CortexDrone forward the snapshot to the optional Xaero minimap/world-map
 * hook. This keeps the map useful even when the drone is far outside the
 * client's entity view distance.</p>
 */
public final class DroneMapSync {
    public static final String CHANNEL = "cortexdrone";

    private static SimpleNetworkWrapper network;
    private int tickCounter;

    public static void register() {
        if (network != null) return;
        network = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        network.registerMessage(DroneMapSyncHandler.class, DroneMapSnapshot.class, 0, Side.CLIENT);
    }

    /**
     * The shared {@code cortexdrone} channel.
     *
     * <p>FML keeps one channel per name; creating a second wrapper for the same
     * name would collide. Every message type on this channel is registered
     * through this accessor.</p>
     */
    public static SimpleNetworkWrapper channel() {
        register();
        return network;
    }

    public void tick(AIDroneManager manager) {
        if (network == null || manager == null) return;
        this.tickCounter++;
        if (this.tickCounter % 20 != 0) return;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;

        network.sendToAll(new DroneMapSnapshot(collect(manager)));
    }

    private static List<DroneMarker> collect(AIDroneManager manager) {
        List<DroneMarker> markers = new ArrayList<DroneMarker>();
        for (DroneController controller : manager.controllers()) {
            if (controller == null) continue;
            Drone drone = controller.drone;
            if (drone == null || drone.isDead) continue;
            markers.add(new DroneMarker(
                    controller.id,
                    controller.name,
                    controller.ownerName,
                    drone.dimension,
                    drone.posX,
                    drone.posY,
                    drone.posZ
            ));
        }
        return markers;
    }
}
