package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.DroneController;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@code /drone despawn <name>} must never let a player destroy someone
 * else's drone.
 *
 * <p>Regression: the command replaced the sender's own controller with any
 * controller whose name matched, then dropped that drone's inventory at the
 * <em>sender's</em> feet. Permission level 0 made it available to everyone.</p>
 */
public class DespawnTargetsTest {
    /**
     * DroneController needs a live Drone entity, so use a name-only stand-in.
     */
    private static DroneController drone(int id, String name, String owner) {
        return DroneController.forTest(id, name, owner);
    }

    private static final DroneController ALICE_DRONE = drone(1, "Alice-AI", "Alice");
    private static final DroneController BOB_DRONE = drone(2, "Bob-AI", "Bob");
    private static final List<DroneController> ALL = Arrays.asList(ALICE_DRONE, BOB_DRONE);

    @Test
    public void aPlayerCannotDespawnAnotherPlayersDroneByName() {
        assertNull("Alice must not be able to despawn Bob's drone",
                DespawnTargets.select("Alice", 0, "Bob-AI", ALICE_DRONE, ALL));
    }

    @Test
    public void aPlayerCannotDespawnAnotherPlayersDroneById() {
        assertNull(DespawnTargets.select("Alice", 0, "#2", ALICE_DRONE, ALL));
        assertNull(DespawnTargets.select("Alice", 0, "2", ALICE_DRONE, ALL));
    }

    @Test
    public void aPlayerCanDespawnTheirOwnDroneByName() {
        assertSame(ALICE_DRONE, DespawnTargets.select("Alice", 0, "Alice-AI", ALICE_DRONE, ALL));
    }

    @Test
    public void noArgumentSelectsTheSendersOwnDrone() {
        assertSame(ALICE_DRONE, DespawnTargets.select("Alice", 0, null, ALICE_DRONE, ALL));
        assertSame(ALICE_DRONE, DespawnTargets.select("Alice", 0, "  ", ALICE_DRONE, ALL));
    }

    @Test
    public void anOperatorMayDespawnAnyDrone() {
        assertSame(BOB_DRONE, DespawnTargets.select("Admin", 2, "Bob-AI", null, ALL));
        assertSame(BOB_DRONE, DespawnTargets.select("Admin", 4, "#2", null, ALL));
    }

    @Test
    public void anUnknownNameSelectsNothing() {
        assertNull(DespawnTargets.select("Alice", 0, "NoSuchDrone", ALICE_DRONE, ALL));
    }

    @Test
    public void ownershipComparisonIsCaseInsensitiveAndTrimmed() {
        assertTrue(DespawnTargets.owns(" alice ", ALICE_DRONE));
        assertFalse(DespawnTargets.owns("Alice", BOB_DRONE));
        assertFalse(DespawnTargets.owns(null, ALICE_DRONE));
        assertFalse(DespawnTargets.owns("Alice", null));
    }

    @Test
    public void emptyControllerListIsSafe() {
        assertNull(DespawnTargets.select("Alice", 0, "Bob-AI", null, Collections.<DroneController>emptyList()));
        assertNull(DespawnTargets.select("Alice", 0, "Bob-AI", null, null));
    }
}
