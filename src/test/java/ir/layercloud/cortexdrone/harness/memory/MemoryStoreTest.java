package ir.layercloud.cortexdrone.harness.memory;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class MemoryStoreTest {
    @Test
    public void remembersFactsAndNamedLocations() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-memory").toFile();
        MemoryStore store = new MemoryStore(root);

        MemoryEntry fact = store.captureFromChat("BlackSnow", 0, 10, 64, 20,
                "remember I prefer torches over glowstone");
        assertNotNull(fact);
        assertEquals("fact", fact.getKind());
        assertFalse(fact.isHasPosition());

        MemoryEntry base = store.captureFromChat("BlackSnow", 0, 120, 64, -40,
                "remember this is my base");
        assertNotNull(base);
        assertEquals("location", base.getKind());
        assertEquals("base", base.getName());
        assertTrue(base.isHasPosition());
        assertEquals(120.0, base.getX(), 0.001);
        assertEquals(64.0, base.getY(), 0.001);
        assertEquals(-40.0, base.getZ(), 0.001);
        assertEquals(0, base.getDimension());

        assertEquals(2, store.count());
        List<MemoryEntry> found = store.search("base", null, 5);
        assertFalse(found.isEmpty());
        assertEquals("base", found.get(0).getName());
    }

    @Test
    public void explicitCoordinatesOverrideCurrentPosition() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-memory-coords").toFile();
        MemoryStore store = new MemoryStore(root);
        MemoryEntry farm = store.captureFromChat("BlackSnow", 0, 1, 2, 3,
                "remember the iron farm is at 120 70 -40");
        assertNotNull(farm);
        assertEquals("location", farm.getKind());
        assertEquals(120.0, farm.getX(), 0.001);
        assertEquals(70.0, farm.getY(), 0.001);
        assertEquals(-40.0, farm.getZ(), 0.001);
    }

    @Test
    public void sameKindAndNameUpdatesInPlace() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-memory-update").toFile();
        MemoryStore store = new MemoryStore(root);
        MemoryEntry first = store.captureFromChat("BlackSnow", 0, 10, 64, 10, "this is my base");
        MemoryEntry second = store.captureFromChat("BlackSnow", 0, 30, 64, 30, "this is my base");
        assertEquals(1, store.count());
        assertEquals(first.getId(), second.getId());
        assertEquals(2, second.getVersion());
        assertEquals(30.0, second.getX(), 0.001);
    }

    @Test
    public void persistsAndForgets() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-memory-persist").toFile();
        MemoryStore store = new MemoryStore(root);
        store.remember("location", "north mine", "diamond mine", true,
                -200, 30, 400, 0, Collections.singletonList("diamond"), true, "test");
        assertEquals(1, new MemoryStore(root).count());

        assertTrue(store.forget("north mine"));
        assertEquals(0, store.count());
        assertEquals(0, new MemoryStore(root).count());
    }

    @Test
    public void pinnedLocationsAppearInContextEvenWhenQueryMisses() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-memory-context").toFile();
        MemoryStore store = new MemoryStore(root);
        store.remember("location", "home", "main base", true,
                12, 65, 34, 0, null, true, "test");
        store.remember("fact", "wood", "likes oak", false,
                0, 0, 0, 0, null, false, "test");
        List<MemoryEntry> context = store.forContext("diamond", 10);
        boolean hasHome = false;
        for (MemoryEntry entry : context) hasHome |= entry.getName().equals("home");
        assertTrue(hasHome);
    }
}
