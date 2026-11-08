package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.LandingSpot;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Behaviour of the passenger landing rules.
 *
 * <p>These are survival rules: every case below is a way a carried player was
 * killed or could be killed by a dismount. The scanner must refuse all of them
 * and still find the nearest safe cell when one exists.</p>
 */
public class LandingScannerTest {
    private static final int FLOOR = 64;

    /** Flat stone plane at y=FLOOR, open air above, whole world loaded. */
    private static final class PlainWorld implements LandingProbe {
        final Set<String> floors = new HashSet<>();
        final Set<String> water = new HashSet<>();
        final Set<String> lava = new HashSet<>();
        final Set<String> leaves = new HashSet<>();
        final Set<String> falling = new HashSet<>();
        final Set<String> roofs = new HashSet<>();
        int height = FLOOR + 1;

        PlainWorld() {
            for (int x = -32; x <= 32; x++) {
                for (int z = -32; z <= 32; z++) {
                    floors.add(x + "," + FLOOR + "," + z);
                }
            }
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isClear(int x, int y, int z) {
            String key = x + "," + y + "," + z;
            return !floors.contains(key) && !water.contains(key) && !lava.contains(key)
                    && !leaves.contains(key) && !falling.contains(key) && !roofs.contains(key);
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return water.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean isDangerousLiquid(int x, int y, int z) {
            return lava.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean isLandingFloor(int x, int y, int z) {
            String key = x + "," + y + "," + z;
            if (water.contains(key) || lava.contains(key) || leaves.contains(key)) return false;
            return floors.contains(key);
        }

        @Override
        public boolean isFallingBlock(int x, int y, int z) {
            return falling.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean canSeeSky(int x, int y, int z) {
            for (String roof : roofs) {
                String[] parts = roof.split(",");
                int rx = Integer.parseInt(parts[0]);
                int ry = Integer.parseInt(parts[1]);
                int rz = Integer.parseInt(parts[2]);
                if (rx == x && rz == z && ry >= y) return false;
            }
            return true;
        }

        @Override
        public int height(int x, int z) {
            int top = FLOOR;
            for (String roof : roofs) {
                String[] parts = roof.split(",");
                if (Integer.parseInt(parts[0]) == x && Integer.parseInt(parts[2]) == z) {
                    top = Math.max(top, Integer.parseInt(parts[1]));
                }
            }
            for (String floor : floors) {
                String[] parts = floor.split(",");
                if (Integer.parseInt(parts[0]) == x && Integer.parseInt(parts[2]) == z) {
                    top = Math.max(top, Integer.parseInt(parts[1]));
                }
            }
            return top + 1;
        }

        @Override
        public String floorName(int x, int y, int z) {
            String key = x + "," + y + "," + z;
            if (leaves.contains(key)) return "Oak Leaves";
            if (water.contains(key)) return "Water";
            if (lava.contains(key)) return "Lava";
            return "Stone";
        }

        void removeFloor(int x, int z) {
            floors.remove(x + "," + FLOOR + "," + z);
        }
    }

    @Test
    public void flatGroundIsASafeLanding() {
        LandingSpot spot = LandingScanner.inspectAt(new PlainWorld(), 0, FLOOR + 1, 0, FLOOR + 1);
        assertNotNull(spot);
        assertTrue(spot.isSafe());
        assertTrue("open sky above flat ground", spot.isOpenSky());
        assertTrue("this is the surface", spot.isSurfaceLevel());
        assertEquals(FLOOR + 1, spot.getY());
    }

    @Test
    public void aVoidWithNoFloorIsUnsafe() {
        PlainWorld world = new PlainWorld();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) world.removeFloor(x, z);
        }
        assertNull("no safe column anywhere",
                LandingScanner.findSafeSpot(world, 0, 0, FLOOR + 1, 10));
        assertFalse(LandingScanner.canDismountHere(world, 0, FLOOR + 30, 0));
    }

    @Test
    public void aHoverThirtyBlocksUpIsNeverADismountPoint() {
        PlainWorld world = new PlainWorld();
        // The exact live death: the drone hovered far above the ground and the
        // rider was released. A flat, safe floor below is not enough.
        assertFalse(LandingScanner.canDismountHere(world, 0, FLOOR + 30, 0));
        // Two blocks above the floor is the maximum safe release.
        assertTrue(LandingScanner.canDismountHere(world, 0, FLOOR + 3, 0));
        assertFalse(LandingScanner.canDismountHere(world, 0, FLOOR + 4, 0));
    }

    @Test
    public void leavesAreNotAFloor() {
        PlainWorld world = new PlainWorld();
        world.removeFloor(4, 4);
        world.leaves.add("4," + FLOOR + ",4");
        world.floors.add("4," + (FLOOR - 6) + ",4");
        // Standing on the canopy is not safe: leaves do not stop a rider.
        assertFalse(LandingScanner.isSafeAt(world, 4, FLOOR + 1, 4));
        assertFalse(LandingScanner.canDismountHere(world, 4, FLOOR + 1, 4));
    }

    @Test
    public void waterAndLavaAreUnsafe() {
        PlainWorld water = new PlainWorld();
        water.removeFloor(6, 6);
        water.water.add("6," + FLOOR + ",6");
        water.water.add("6," + (FLOOR + 1) + ",6");
        assertFalse(LandingScanner.isSafeAt(water, 6, FLOOR + 1, 6));

        PlainWorld lava = new PlainWorld();
        lava.removeFloor(7, 7);
        lava.lava.add("7," + FLOOR + ",7");
        assertFalse(LandingScanner.isSafeAt(lava, 7, FLOOR + 1, 7));
    }

    @Test
    public void aOneBlockPocketSuffocates() {
        PlainWorld world = new PlainWorld();
        world.roofs.add("0," + (FLOOR + 2) + ",0");
        // Feet clear, head blocked: the player suffocates, so this is unsafe.
        assertFalse(LandingScanner.isSafeAt(world, 0, FLOOR + 1, 0));
        assertNull("a covered column with no second cell has no spot",
                LandingScanner.inspectColumn(world, 0, 0, FLOOR + 1));
    }

    @Test
    public void fallingBlockOverheadIsUnsafe() {
        PlainWorld world = new PlainWorld();
        // Two cells above the head: the pocket itself is clear, but the column
        // would bury the passenger when the sand/gravel falls.
        world.falling.add("2," + (FLOOR + 3) + ",2");
        LandingSpot spot = LandingScanner.inspectAt(world, 2, FLOOR + 1, 2, FLOOR + 1);
        assertNotNull(spot);
        assertFalse(spot.isSafe());
        assertEquals("falling block overhead", spot.getReason());
    }

    @Test
    public void aCoveredCaveFloorIsSafeButNotSurfaceLevel() {
        PlainWorld world = new PlainWorld();
        world.roofs.add("10," + (FLOOR + 12) + ",10");
        LandingSpot spot = LandingScanner.inspectAt(world, 10, FLOOR + 1, 10, FLOOR + 1);
        assertNotNull(spot);
        assertTrue(spot.isSafe());
        assertFalse("a roof is overhead", spot.isOpenSky());
        assertFalse("this is not the surface", spot.isSurfaceLevel());
    }

    @Test
    public void aPassengerIsNeverOfferedACoveredCaveFloor() {
        PlainWorld world = new PlainWorld();
        // A roof over the whole search area makes every column covered. The
        // landing is physically safe, but entering a cave to reach it would
        // crush the rider, so a passenger-only search must find nothing.
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.roofs.add(x + "," + (FLOOR + 12) + "," + z);
            }
        }
        assertNull("a passenger must not be flown into a cave",
                LandingScanner.findSafeSpot(world, 0, 0, FLOOR + 1, 8, true));
        assertNotNull("a drone/mob landing may still use the cave floor",
                LandingScanner.findSafeSpot(world, 0, 0, FLOOR + 1, 8, false));
    }

    @Test
    public void theNearestSafeColumnIsFoundWhenTheTargetIsUnsafe() {
        PlainWorld world = new PlainWorld();
        // A 5x5 lava pool centred on the request; safe ground surrounds it.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.removeFloor(x, z);
                world.lava.add(x + "," + FLOOR + "," + z);
            }
        }
        LandingSpot spot = LandingScanner.findSafeSpot(world, 0, 0, FLOOR + 1, 8);
        assertNotNull("safe ground exists just outside the pool", spot);
        assertTrue(spot.isSafe());
        assertTrue("the spot must not be inside the pool",
                Math.abs(spot.getX()) > 2 || Math.abs(spot.getZ()) > 2);
    }

    @Test
    public void theFloorMayBeAtMostTwoBlocksBelowTheHoverPoint() {
        PlainWorld world = new PlainWorld();
        // `isSafe` is exact: the feet cell must sit directly on a floor.
        assertTrue(LandingScanner.isSafeAt(world, 5, FLOOR + 1, 5));
        assertFalse("one block in the air is not a standing cell",
                LandingScanner.isSafeAt(world, 5, FLOOR + 2, 5));

        // `canDismountHere` is the release rule: the rider may drop up to two
        // blocks. Two is safe, three is refused.
        assertTrue("two-block drop", LandingScanner.canDismountHere(world, 5, FLOOR + 3, 5));
        assertFalse("three-block drop", LandingScanner.canDismountHere(world, 5, FLOOR + 4, 5));
    }
}
