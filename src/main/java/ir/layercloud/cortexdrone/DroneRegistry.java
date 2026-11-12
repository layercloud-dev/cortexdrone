package ir.layercloud.cortexdrone;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The live drone-controller registry.
 *
 * <p>Owns identity assignment, the id/owner indexes and the despawned-UUID
 * memory. Callers get a read-only view, so nothing outside this class can add or
 * remove a controller behind the manager's back (the old
 * {@code getControllersById()} handed out the mutable map itself).</p>
 *
 * <p>It deals only in {@link DroneController}s: entity construction and
 * entity-UUID matching live in {@link DroneLifecycle}, which keeps this class
 * unit-testable without an OpenComputers entity on the classpath.</p>
 */
public final class DroneRegistry {
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, DroneController> byId = new ConcurrentHashMap<>();
    private final Map<String, DroneController> byOwner = new ConcurrentHashMap<>();
    private final Set<UUID> despawned = Collections.synchronizedSet(new HashSet<>());

    /**
     * Every live controller, in no particular order.
     */
    public Collection<DroneController> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /**
     * The next controller id. Monotonic for the lifetime of the server.
     */
    public int nextId() {
        return this.nextId.getAndIncrement();
    }

    /**
     * Adds a controller to both indexes.
     *
     * <p>A server-owned drone is not indexed by owner: "Server" is not a player
     * and must never resolve through {@link #byOwner(String)}.</p>
     */
    public void index(DroneController controller) {
        if (controller == null) return;
        this.byId.put(controller.id, controller);
        if (controller.ownerName != null && !DroneController.SERVER_OWNER.equalsIgnoreCase(controller.ownerName)) {
            this.byOwner.put(CortexDroneMod.normKey(controller.ownerName), controller);
        }
    }

    /**
     * Drops a controller and re-points the owner index at another live drone.
     */
    public void remove(DroneController controller) {
        if (controller == null) return;
        this.byId.remove(controller.id);
        if (controller.ownerName == null) return;
        String key = CortexDroneMod.normKey(controller.ownerName);
        this.byOwner.remove(key, controller);
        for (DroneController other : this.byId.values()) {
            if (other != null && other.drone != null && !other.drone.isDead
                    && controller.ownerName.equalsIgnoreCase(other.ownerName)) {
                this.byOwner.put(key, other);
                return;
            }
        }
    }

    /**
     * Finds a controller by the entity it currently points at.
     */
    public DroneController findByControllerEntity(Object entity) {
        if (entity == null) return null;
        for (DroneController controller : this.byId.values()) {
            if (controller.drone == entity) return controller;
        }
        return null;
    }

    /**
     * The controller owned by {@code playerName}, or {@code null}.
     */
    public DroneController byOwner(String playerName) {
        if (playerName == null) return null;
        return this.byOwner.get(CortexDroneMod.normKey(playerName));
    }

    /**
     * Re-indexes an existing controller under a new owner.
     */
    public void reassignOwner(DroneController controller, String ownerName) {
        if (controller == null || ownerName == null) return;
        controller.ownerName = ownerName;
        this.byOwner.put(CortexDroneMod.normKey(ownerName), controller);
    }

    /**
     * Records that this entity was deliberately despawned by the mod.
     */
    public void markDespawned(UUID entityUuid) {
        if (entityUuid != null) this.despawned.add(entityUuid);
    }

    /**
     * True when the entity was despawned by this mod (so reconnect must skip it).
     */
    public boolean wasDespawned(UUID entityUuid) {
        return entityUuid != null && this.despawned.contains(entityUuid);
    }
}
