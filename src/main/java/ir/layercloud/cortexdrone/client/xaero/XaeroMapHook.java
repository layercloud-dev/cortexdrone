package ir.layercloud.cortexdrone.client.xaero;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import ir.layercloud.cortexdrone.client.ClientDroneMarkers;
import ir.layercloud.cortexdrone.network.DroneMarker;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional client hook for Xaero's Minimap and Xaero's World Map.
 *
 * <p>The hook is loaded only on the physical client. It detects Xaero by class
 * name, so CortexDrone keeps compiling and running when Xaero is absent. Each
 * live CortexDrone becomes a temporary waypoint in the current Xaero waypoint
 * set. Temporary waypoints are never written to the player's waypoint files,
 * and because both Xaero mods share the minimap waypoint set the marker is
 * visible on the minimap and the full world map at the same time.</p>
 */
public final class XaeroMapHook {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int WAYPOINT_COLOR = 11; // Xaero colour table: aqua
    private static final String WAYPOINT_SYMBOL = "D";

    private static XaeroMapHook instance;

    private final Map<Integer, MarkerState> markerStates = new HashMap<Integer, MarkerState>();

    private boolean detected;
    private boolean available;
    private int ticks;
    private int failures;

    private Class<?> sessionClass;
    private Class<?> waypointsManagerClass;
    private Class<?> waypointWorldClass;
    private Class<?> waypointSetClass;
    private Class<?> containerClass;
    private Class<?> waypointClass;

    private Method getCurrentSession;
    private Method getWaypointsManager;
    private Method getCurrentWorld;
    private Method getCurrentSet;
    private Method getContainer;
    private Method getKey;
    private Method getDimensionDivision;
    private Method getList;
    private Method setName;
    private Method setX;
    private Method setY;
    private Method setZ;
    private Constructor<?> waypointConstructor;

    private Field worldMapSupportField;
    private Method requestWorldMapRefresh;

    private XaeroMapHook() {
    }

    public static void register() {
        if (instance != null) return;
        instance = new XaeroMapHook();
        FMLCommonHandler.instance().bus().register(instance);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            tick();
        } catch (Throwable t) {
            this.failures++;
            if (this.failures == 1) {
                LOG.log(Level.WARNING, "Xaero minimap/world-map hook encountered an error; "
                        + "map markers will be retried", t);
            }
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientDroneMarkers.clear();
        clearMarkers();
    }

    private void tick() throws Exception {
        if (!detect()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            ClientDroneMarkers.clear();
            clearMarkers();
            return;
        }

        Object session = this.getCurrentSession.invoke(null);
        if (session == null) {
            clearMarkers();
            return;
        }
        Object waypointsManager = this.getWaypointsManager.invoke(session);
        if (waypointsManager == null) {
            clearMarkers();
            return;
        }
        Object waypointWorld = this.getCurrentWorld.invoke(waypointsManager);
        if (waypointWorld == null) {
            clearMarkers();
            return;
        }
        Object waypointSet = this.getCurrentSet.invoke(waypointWorld);
        if (waypointSet == null) {
            clearMarkers();
            return;
        }

        Object container = this.getContainer.invoke(waypointWorld);
        String containerKey = (String) this.getKey.invoke(container);
        double division = ((Number) this.getDimensionDivision.invoke(waypointsManager, containerKey))
                .doubleValue();
        List<Object> list = waypointList(waypointSet);

        boolean changed = false;
        Set<Integer> seen = new HashSet<Integer>();
        int playerDimension = mc.thePlayer.dimension;

        for (DroneMarker marker : ClientDroneMarkers.get()) {
            if (marker.getDimension() != playerDimension) continue;
            seen.add(marker.getId());

            int x = (int) Math.floor(marker.getX() * division);
            int y = (int) Math.floor(marker.getY());
            int z = (int) Math.floor(marker.getZ() * division);
            String label = labelFor(marker);

            MarkerState state = this.markerStates.get(marker.getId());
            if (state == null || state.set != waypointSet || !list.contains(state.waypoint)) {
                if (state != null) removeState(state);
                Object waypoint = this.waypointConstructor.newInstance(
                        x, y, z, label, WAYPOINT_SYMBOL, WAYPOINT_COLOR, 0, Boolean.TRUE);
                list.add(waypoint);
                this.markerStates.put(marker.getId(),
                        new MarkerState(waypoint, waypointSet, x, y, z, label));
                changed = true;
            } else if (state.x != x || state.y != y || state.z != z
                    || !state.label.equals(label)) {
                this.setX.invoke(state.waypoint, x);
                this.setY.invoke(state.waypoint, y);
                this.setZ.invoke(state.waypoint, z);
                if (!state.label.equals(label)) {
                    this.setName.invoke(state.waypoint, label);
                }
                state.x = x;
                state.y = y;
                state.z = z;
                state.label = label;
                changed = true;
            }
        }

        Iterator<Map.Entry<Integer, MarkerState>> iterator =
                this.markerStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, MarkerState> entry = iterator.next();
            if (seen.contains(entry.getKey())) continue;
            removeState(entry.getValue());
            iterator.remove();
            changed = true;
        }

        this.ticks++;
        if (changed || (this.ticks % 20 == 0 && !this.markerStates.isEmpty())) {
            requestWorldMapRefresh();
        }
    }

    private boolean detect() {
        if (this.detected) return this.available;
        this.detected = true;
        try {
            this.sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            this.waypointsManagerClass =
                    Class.forName("xaero.common.minimap.waypoints.WaypointsManager");
            this.waypointWorldClass =
                    Class.forName("xaero.common.minimap.waypoints.WaypointWorld");
            this.waypointSetClass =
                    Class.forName("xaero.common.minimap.waypoints.WaypointSet");
            this.containerClass =
                    Class.forName("xaero.common.minimap.waypoints.WaypointWorldContainer");
            this.waypointClass =
                    Class.forName("xaero.common.minimap.waypoints.Waypoint");

            this.getCurrentSession = this.sessionClass.getMethod("getCurrentSession");
            this.getWaypointsManager = this.sessionClass.getMethod("getWaypointsManager");
            this.getCurrentWorld = this.waypointsManagerClass.getMethod("getCurrentWorld");
            this.getCurrentSet = this.waypointWorldClass.getMethod("getCurrentSet");
            this.getContainer = this.waypointWorldClass.getMethod("getContainer");
            this.getKey = this.containerClass.getMethod("getKey");
            this.getDimensionDivision = this.waypointsManagerClass
                    .getMethod("getDimensionDivision", String.class);
            this.getList = this.waypointSetClass.getMethod("getList");
            this.setName = this.waypointClass.getMethod("setName", String.class);
            this.setX = this.waypointClass.getMethod("setX", int.class);
            this.setY = this.waypointClass.getMethod("setY", int.class);
            this.setZ = this.waypointClass.getMethod("setZ", int.class);
            this.waypointConstructor = this.waypointClass.getConstructor(
                    int.class, int.class, int.class, String.class, String.class,
                    int.class, int.class, boolean.class);

            detectWorldMap();
            this.available = true;
        } catch (ClassNotFoundException notInstalled) {
            this.available = false;
        } catch (Throwable t) {
            this.available = false;
            LOG.log(Level.WARNING, "Xaero's minimap/world map is installed but its waypoint API "
                    + "does not match the supported version", t);
        }
        return this.available;
    }

    private void detectWorldMap() {
        try {
            Class<?> supportModsClass = Class.forName("xaero.map.mods.SupportMods");
            Class<?> supportMinimapClass =
                    Class.forName("xaero.map.mods.SupportXaeroMinimap");
            this.worldMapSupportField = supportModsClass.getField("xaeroMinimap");
            this.requestWorldMapRefresh =
                    supportMinimapClass.getMethod("requestWaypointsRefresh");
        } catch (Throwable ignored) {
            this.worldMapSupportField = null;
            this.requestWorldMapRefresh = null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> waypointList(Object waypointSet) throws Exception {
        return (List<Object>) this.getList.invoke(waypointSet);
    }

    private void removeState(MarkerState state) {
        try {
            waypointList(state.set).remove(state.waypoint);
        } catch (Throwable ignored) {
        }
    }

    private void clearMarkers() {
        if (this.markerStates.isEmpty()) return;
        for (MarkerState state : this.markerStates.values()) {
            removeState(state);
        }
        this.markerStates.clear();
        requestWorldMapRefresh();
    }

    private void requestWorldMapRefresh() {
        if (this.worldMapSupportField == null || this.requestWorldMapRefresh == null) return;
        try {
            Object support = this.worldMapSupportField.get(null);
            if (support != null) {
                this.requestWorldMapRefresh.invoke(support);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String labelFor(DroneMarker marker) {
        String name = sanitize(marker.getName());
        if (name.length() == 0) name = sanitize(marker.getOwner());
        if (name.length() == 0) name = "Drone";

        String label = name.regionMatches(true, 0, "drone", 0, 5)
                ? name
                : "Drone " + name;
        if (label.length() > 32) label = label.substring(0, 32);
        return label;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c == ':' || c == '\u00a7') continue;
            builder.append(c);
        }
        return builder.toString().trim();
    }

    private static final class MarkerState {
        private final Object waypoint;
        private final Object set;
        private int x;
        private int y;
        private int z;
        private String label;

        private MarkerState(Object waypoint, Object set, int x, int y, int z, String label) {
            this.waypoint = waypoint;
            this.set = set;
            this.x = x;
            this.y = y;
            this.z = z;
            this.label = label;
        }
    }
}
