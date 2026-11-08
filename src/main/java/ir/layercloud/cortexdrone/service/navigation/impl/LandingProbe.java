package ir.layercloud.cortexdrone.service.navigation.impl;

/**
 * The minimal read-only world view {@link LandingScanner} needs.
 *
 * <p>Extracted so the landing rules can be unit-tested without a live
 * {@code World}: the scanner's decisions are survival rules, and a broken rule
 * kills a passenger, so they must be covered by plain JUnit tests rather than
 * only by string-matching assertions.</p>
 *
 * <p>Implementations must never mutate the world.</p>
 */
public interface LandingProbe {
    /** True when the chunk is loaded and the block can be read. */
    boolean blockExists(int x, int y, int z);

    /** True for a cell with no collision box: air, a plant, a liquid. */
    boolean isClear(int x, int y, int z);

    /** True for water or flowing water. */
    boolean isWater(int x, int y, int z);

    /** True for lava or flowing lava. */
    boolean isDangerousLiquid(int x, int y, int z);

    /** True when the block stops a falling entity (solid, no plants/leaves). */
    boolean isLandingFloor(int x, int y, int z);

    /** True for sand/gravel and other falling blocks. */
    boolean isFallingBlock(int x, int y, int z);

    /** True when nothing blocks the sky above this cell. */
    boolean canSeeSky(int x, int y, int z);

    /** The highest solid Y in this column (vanilla heightmap). */
    int height(int x, int z);

    /** Human-readable floor name used in tool results. */
    String floorName(int x, int y, int z);
}
