package ir.layercloud.cortexdrone.client;

import ir.layercloud.cortexdrone.network.DroneMarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Latest drone snapshot received from the server.
 *
 * <p>The network handler publishes a fully built immutable list; the client
 * tick hook reads the volatile reference. No Minecraft or Xaero class is
 * touched here, so the network handler remains safe to load on a dedicated
 * server.</p>
 */
public final class ClientDroneMarkers {
    private static volatile List<DroneMarker> markers = Collections.emptyList();

    private ClientDroneMarkers() {
    }

    public static void accept(List<DroneMarker> next) {
        if (next == null || next.isEmpty()) {
            markers = Collections.emptyList();
        } else {
            markers = Collections.unmodifiableList(new ArrayList<DroneMarker>(next));
        }
    }

    public static void clear() {
        markers = Collections.emptyList();
    }

    public static List<DroneMarker> get() {
        return markers;
    }
}
