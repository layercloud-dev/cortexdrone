package ir.layercloud.cortexdrone;

import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.*;

/**
 * Controller identity and indexing rules.
 *
 * <p>The registry replaced a pair of mutable maps that {@code AIDroneManager}
 * handed to every caller. These tests pin the identity rules: unique ids, owner
 * re-pointing after a removal, deliberate-despawn memory, and the read-only view
 * that stops outside code mutating the index.</p>
 */
public class DroneRegistryTest {
    /**
     * Drone is a live entity class; a headless instance is not constructible.
     */
    private static DroneController controller(int id, String owner) {
        return DroneController.forTest(id, "drone-" + id, owner);
    }

    @Test
    public void indexedControllersAreCounted() {
        DroneRegistry registry = new DroneRegistry();
        DroneController first = controller(1, "Alice");
        DroneController second = controller(2, "Bob");
        registry.index(first);
        registry.index(second);
        assertEquals(2, registry.size());
        assertFalse(registry.isEmpty());
    }

    @Test
    public void ownerLookupIsCaseAndFormatInsensitive() {
        DroneRegistry registry = new DroneRegistry();
        DroneController alice = controller(1, "Alice");
        registry.index(alice);
        assertSame(alice, registry.byOwner("Alice"));
        assertSame(alice, registry.byOwner("alice"));
        assertNull(registry.byOwner("Bob"));
        assertNull(registry.byOwner(null));
    }

    @Test
    public void serverDronesAreNotIndexedByOwner() {
        DroneRegistry registry = new DroneRegistry();
        registry.index(controller(1, "Server"));
        assertNull(registry.byOwner("Server"));
        assertEquals(1, registry.size());
    }

    @Test
    public void removingAControllerRepointsTheOwnerIndex() {
        DroneRegistry registry = new DroneRegistry();
        DroneController first = controller(1, "Alice");
        DroneController second = controller(2, "Alice");
        registry.index(first);
        registry.index(second);
        registry.remove(first);
        assertSame("the owner index must point at the surviving drone",
                second, registry.byOwner("Alice"));
        assertEquals(1, registry.size());
    }

    @Test
    public void reassigningAnOwnerUpdatesTheIndex() {
        DroneRegistry registry = new DroneRegistry();
        DroneController drone = controller(1, "Alice");
        registry.index(drone);
        registry.reassignOwner(drone, "Bob");
        assertSame(drone, registry.byOwner("Bob"));
        assertEquals("Bob", drone.ownerName);
    }

    @Test
    public void despawnedEntitiesAreRemembered() {
        DroneRegistry registry = new DroneRegistry();
        java.util.UUID entity = java.util.UUID.randomUUID();
        assertFalse(registry.wasDespawned(entity));
        registry.markDespawned(entity);
        assertTrue("a despawned drone must not be re-adopted",
                registry.wasDespawned(entity));
        assertFalse(registry.wasDespawned(java.util.UUID.randomUUID()));
        registry.markDespawned(null);
        assertFalse(registry.wasDespawned(null));
    }

    @Test
    public void idsAreMonotonic() {
        DroneRegistry registry = new DroneRegistry();
        int first = registry.nextId();
        int second = registry.nextId();
        assertTrue("ids must never be reused", second > first);
    }

    @Test
    public void theControllerViewIsReadOnly() {
        DroneRegistry registry = new DroneRegistry();
        registry.index(controller(1, "Alice"));
        Collection<DroneController> view = registry.all();
        try {
            view.clear();
            fail("the registry view must not allow outside mutation");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(1, registry.size());
    }

    @Test
    public void findByEntityIsNullSafe() {
        DroneRegistry registry = new DroneRegistry();
        assertNull(registry.findByControllerEntity(null));
        assertNull(registry.findByControllerEntity(new Object()));
    }
}
