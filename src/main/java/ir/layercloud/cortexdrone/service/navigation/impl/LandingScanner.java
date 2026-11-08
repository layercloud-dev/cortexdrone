package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.LandingSpot;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Read-only landing-site scanner.
 *
 * <p>A carried passenger dies for one reason: the drone dismounts them where
 * there is no floor. The live logs show two deaths that way — a player dropped
 * at a death coordinate far above the ground ("hit the ground too hard"), and
 * another dropped over a tree. This class answers one question only: "may I set
 * a passenger down on this column, and if not, where is the nearest column that
 * I may?" It never mutates the world.</p>
 *
 * <p>Safety rules, all mandatory:</p>
 * <ol>
 *   <li>the first block below the feet must stop a falling entity: solid, not
 *       liquid, not leaves/plants/cactus/fire;</li>
 *   <li>the feet cell and the head cell form a clear 2-block pocket;</li>
 *   <li>neither cell is lava or water;</li>
 *   <li>the fall from the hover point to the floor is at most two blocks;</li>
 *   <li>falling blocks are not directly above the pocket.</li>
 * </ol>
 */
@UtilityClass
public final class LandingScanner {
    /** Vanilla safe fall distance is 3; a passenger should never need more than 2. */
    public static final int MAX_SAFE_DROP = 2;
    /** How far below the requested Y a surface landing may be found. */
    public static final int MAX_SCAN_DOWN = 48;
    /** How far above the requested Y a landing may be found (hill, canopy gap). */
    public static final int MAX_SCAN_UP = 8;
    private static final int MIN_Y = 3;
    private static final int MAX_Y = 250;
    /**
     * Columns a single search may inspect. A landing search runs on the server
     * thread; 625 columns x 57 rows of block reads would stall a tick. The ring
     * order finds a normal landing within a handful of columns, so this cap
     * only bounds the pathological "no landing anywhere" case.
     */
    private static final int MAX_COLUMNS = 384;

    /** World-backed probe used by every public entry point. */
    public static LandingProbe probe(World world) {
        return new WorldLandingProbe(world);
    }

    /**
     * Finds the best safe landing column within {@code radius} of the request.
     *
     * @param world           server world
     * @param cx              requested centre X
     * @param cz              requested centre Z
     * @param refY            the Y the caller aimed at (a preference, not a limit)
     * @param radius          horizontal search radius, clamped to 1..24
     * @param requireOpenSky  skip covered pockets; a passenger is only flown
     *                        into open sky, because entering a cave crushes them
     * @return the best safe spot, or {@code null} when every candidate is unsafe
     */
    public static LandingSpot findSafeSpot(World world, int cx, int cz, int refY,
                                           int radius, boolean requireOpenSky) {
        return findSafeSpot(probe(world), cx, cz, refY, radius, requireOpenSky);
    }

    /**
     * Finds the best safe landing column within {@code radius} of the request.
     */
    public static LandingSpot findSafeSpot(LandingProbe probe, int cx, int cz, int refY,
                                           int radius, boolean requireOpenSky) {
        if (probe == null) return null;
        int r = Math.max(1, Math.min(24, radius));
        LandingSpot best = null;
        double bestScore = Double.MAX_VALUE;
        // Ring order returns the nearest column first, which is what a landing
        // search wants and keeps the common case cheap: one or two rings.
        int budget = MAX_COLUMNS;
        for (int ring = 0; ring <= r && budget > 0; ring++) {
            LandingSpot ringBest = null;
            double ringScore = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    budget--;
                    LandingSpot spot = inspectColumn(probe, cx + dx, cz + dz, refY);
                    if (spot == null || !spot.isSafe()) continue;
                    if (requireOpenSky && !spot.isOpenSky()) continue;
                    double score = Math.sqrt(dx * dx + dz * dz)
                            + Math.abs(spot.getY() - refY) * 0.6
                            - (spot.isOpenSky() ? 1.5 : 0.0)
                            - (spot.isSurfaceLevel() ? 0.5 : 0.0);
                    if (score < ringScore) {
                        ringScore = score;
                        ringBest = spot;
                    }
                }
            }
            if (ringBest != null && ringScore < bestScore) {
                // An outer ring is only better when it is dramatically clearer;
                // the first usable ring is normally the right answer.
                bestScore = ringScore;
                best = ringBest;
                break;
            }
        }
        return best;
    }

    public static LandingSpot findSafeSpot(World world, int cx, int cz, int refY, int radius) {
        return findSafeSpot(world, cx, cz, refY, radius, false);
    }

    /** Probe-backed variant for callers that already built a probe. */
    public static LandingSpot findSafeSpot(LandingProbe probe, int cx, int cz, int refY, int radius) {
        return findSafeSpot(probe, cx, cz, refY, radius, false);
    }

    /**
     * Evaluates one X/Z column and returns the safe pocket closest to
     * {@code refY}. Surface pockets are preferred over covered ones at equal
     * distance because a passenger under an overhang is harder to recover.
     */
    public static LandingSpot inspectColumn(World world, int x, int z, int refY) {
        return inspectColumn(probe(world), x, z, refY);
    }

    public static LandingSpot inspectColumn(LandingProbe probe, int x, int z, int refY) {
        if (probe == null || !probe.blockExists(x, MIN_Y, z)) return null;
        // Search a window around the reference Y. Climbing to the surface here
        // would be both expensive and wrong: a rider cannot be flown up to a
        // surface landing without a shaft, so a deep column is handled by the
        // cave fallback in findDismountSpot, not by this scan.
        int from = Math.min(MAX_Y, refY + MAX_SCAN_UP);
        int to = Math.max(MIN_Y, refY - MAX_SCAN_DOWN);
        LandingSpot best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = from; y >= to; y--) {
            if (!probe.blockExists(x, y, z)) continue;
            LandingSpot spot = inspectAt(probe, x, y, z, refY);
            if (spot == null || !spot.isSafe()) continue;
            double score = Math.abs(spot.getY() - refY)
                    - (spot.isOpenSky() ? 4.0 : 0.0)
                    - (spot.isSurfaceLevel() ? 1.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        return best;
    }

    /**
     * Evaluates exactly one feet position.
     *
     * @param feetY the block a passenger's feet would occupy
     */
    public static LandingSpot inspectAt(World world, int x, int feetY, int z, int refY) {
        return inspectAt(probe(world), x, feetY, z, refY);
    }

    public static LandingSpot inspectAt(LandingProbe probe, int x, int feetY, int z, int refY) {
        if (probe == null) return null;
        if (feetY < MIN_Y || feetY >= MAX_Y) {
            return LandingSpot.unsafe(x, feetY, z, "outside the world height");
        }
        if (!probe.blockExists(x, feetY, z)) return null;

        if (probe.isDangerousLiquid(x, feetY, z) || probe.isDangerousLiquid(x, feetY + 1, z)
                || probe.isDangerousLiquid(x, feetY - 1, z)) {
            return LandingSpot.unsafe(x, feetY, z, "lava");
        }
        if (probe.isWater(x, feetY, z) || probe.isWater(x, feetY + 1, z)
                || probe.isWater(x, feetY - 1, z)) {
            return LandingSpot.unsafe(x, feetY, z, "water");
        }
        if (!probe.isClear(x, feetY, z) || !probe.isClear(x, feetY + 1, z)) {
            return LandingSpot.unsafe(x, feetY, z, "no 2-block pocket");
        }
        if (!probe.isLandingFloor(x, feetY - 1, z)) {
            return LandingSpot.unsafe(x, feetY, z, "no solid floor below");
        }
        // A falling block above the pocket would crush the passenger after the
        // drone leaves; refuse columns with sand/gravel directly overhead.
        if (probe.isFallingBlock(x, feetY + 2, z) || probe.isFallingBlock(x, feetY + 3, z)) {
            return LandingSpot.unsafe(x, feetY, z, "falling block overhead");
        }

        boolean openSky = probe.canSeeSky(x, feetY + 1, z);
        boolean surface = feetY >= probe.height(x, z) - 2;
        return new LandingSpot(x, feetY, z, true, openSky, surface,
                openSky ? "open sky" : "covered but clear",
                probe.floorName(x, feetY - 1, z));
    }

    /** True when this exact block is a safe passenger feet position. */
    public static boolean isSafe(World world, int x, int y, int z) {
        LandingSpot spot = inspectAt(world, x, y, z, y);
        return spot != null && spot.isSafe();
    }

    /**
     * True when a passenger released at the drone's hover point lands safely.
     * The first non-clear block below wins: leaves do not stop a rider, so the
     * search keeps falling until it finds a real floor.
     */
    public static boolean canDismountHere(World world, double x, double y, double z) {
        return canDismountHere(probe(world), MathHelper.floor_double(x),
                MathHelper.floor_double(y), MathHelper.floor_double(z));
    }

    public static boolean canDismountHere(LandingProbe probe, int bx, int startY, int bz) {
        if (probe == null) return false;
        // Finding a floor at `startY - drop` means the rider falls `drop - 1`
        // blocks, so the loop reaches one past MAX_SAFE_DROP.
        for (int drop = 0; drop <= MAX_SAFE_DROP + 1; drop++) {
            int cell = startY - drop;
            if (probe.isLandingFloor(bx, cell, bz)) {
                return isSafe(probe, bx, cell + 1, bz);
            }
            if (probe.isWater(bx, cell, bz) || probe.isDangerousLiquid(bx, cell, bz)) return false;
            // Anything else that is not clear (leaves, a plant, fire) also
            // never stops a rider: falling onto it continues downward.
            if (!probe.isClear(bx, cell, bz)) return false;
        }
        return false;
    }

    private static boolean isSafe(LandingProbe probe, int x, int y, int z) {
        LandingSpot spot = inspectAt(probe, x, y, z, y);
        return spot != null && spot.isSafe();
    }

    /** Probe-backed variant of {@link #isSafe(World, int, int, int)}. */
    public static boolean isSafeAt(LandingProbe probe, int x, int y, int z) {
        return isSafe(probe, x, y, z);
    }

    /** The nearest hover point where {@link #canDismountHere} is true. */
    public static LandingSpot findDismountSpot(World world, double x, double y, double z,
                                               int radius) {
        return findDismountSpot(world, x, y, z, radius, false);
    }

    /**
     * The nearest hover point where {@link #canDismountHere} is true.
     *
     * @param requireOpenSky only accept a spot with sky access; used for a
     *                       passenger when the drone is above ground and could
     *                       still fly somewhere better
     */
    public static LandingSpot findDismountSpot(World world, double x, double y, double z,
                                               int radius, boolean requireOpenSky) {
        if (world == null) return null;
        LandingProbe probe = probe(world);
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        int refY = MathHelper.floor_double(y);
        LandingSpot spot = findSafeSpot(probe, bx, bz, refY, radius, requireOpenSky);
        if (spot != null) return spot;
        // The reference Y may be far higher than the ground (the drone hovers
        // above a hillside). Walk down to the first landing before giving up.
        for (int drop = 0; drop <= Math.min(MAX_SCAN_DOWN, 32); drop++) {
            int feet = refY - drop;
            LandingSpot below = inspectAt(probe, bx, feet, bz, refY);
            if (below != null && below.isSafe()) {
                if (!requireOpenSky || below.isOpenSky()) return below;
            }
        }
        // A surface-level search found nothing. A cave floor directly under the
        // drone is still a legal landing when the drone is already underground;
        // there is nowhere else to fly a rider.
        int surface = probe.height(bx, bz);
        if (Math.abs(refY - surface) > 32 && surface > MIN_Y) {
            return findSafeSpot(probe, bx, bz, surface, radius,
                    requireOpenSky);
        }
        return null;
    }

    /** Builds a probe over a live world. Package-private for the tests. */
    static final class WorldLandingProbe implements LandingProbe {
        private final World world;

        WorldLandingProbe(World world) {
            this.world = world;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return world != null && world.blockExists(x, y, z);
        }

        @Override
        public boolean isClear(int x, int y, int z) {
            Block block = world.getBlock(x, y, z);
            if (block == null || block.getMaterial() == Material.air) return true;
            return ItemClassifier.isPassableVoxel(world, x, y, z)
                    && block.getCollisionBoundingBoxFromPool(world, x, y, z) == null;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            Block block = world.getBlock(x, y, z);
            return block != null && block.getMaterial() == Material.water;
        }

        @Override
        public boolean isDangerousLiquid(int x, int y, int z) {
            return ItemClassifier.isDangerousLiquid(world.getBlock(x, y, z));
        }

        @Override
        public boolean isLandingFloor(int x, int y, int z) {
            Block block = world.getBlock(x, y, z);
            if (block == null || block.getMaterial() == Material.air) return false;
            if (ItemClassifier.isLiquidBlock(block)) return false;
            Material material = block.getMaterial();
            if (material == Material.plants || material == Material.vine
                    || material == Material.fire || material == Material.cactus) return false;
            if (ItemClassifier.isLeafBlock(block) || ItemClassifier.isCropOrReedBlock(block)) return false;
            return block.getCollisionBoundingBoxFromPool(world, x, y, z) != null;
        }

        @Override
        public boolean isFallingBlock(int x, int y, int z) {
            return world.getBlock(x, y, z) instanceof net.minecraft.block.BlockFalling;
        }

        @Override
        public boolean canSeeSky(int x, int y, int z) {
            return world.canBlockSeeTheSky(x, y, z);
        }

        @Override
        public int height(int x, int z) {
            return world.getHeightValue(x, z);
        }

        @Override
        public String floorName(int x, int y, int z) {
            Block block = world.getBlock(x, y, z);
            return ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(x, y, z));
        }
    }
}
