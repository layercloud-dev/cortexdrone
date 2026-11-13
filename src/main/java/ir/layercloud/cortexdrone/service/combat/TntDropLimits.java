package ir.layercloud.cortexdrone.service.combat;

/**
 * Safety and physics bounds for a TNT drop.
 *
 * <p>A dropped TNT is ordnance: the drone must be outside the blast when the
 * fuse ends. Vanilla TNT explodes with a power of 4, which destroys blocks out
 * to roughly four blocks and damages entities out to eight. The safety radius
 * is therefore larger than the destruction radius, and the drop is refused
 * rather than "probably survivable".</p>
 */
public final class TntDropLimits {
    /**
     * Vanilla TNT explosion power ({@code EntityTNTPrimed.explode}).
     */
    public static final float BLAST_POWER = 4.0F;
    /**
     * Block radius destroyed by a power-4 explosion.
     */
    public static final int BLAST_BLOCK_RADIUS = 4;
    /**
     * Distance the drone must keep from the detonation point. Entity damage
     * reaches about twice the block radius; anything closer than this can hull
     * the drone or kill its rider.
     */
    public static final double SAFE_DISTANCE = 9.0D;

    /**
     * Shortest fuse accepted, in ticks. Ten ticks is half a second: enough for
     * the entity to be spawned and begin falling.
     */
    public static final int MIN_FUSE_TICKS = 10;
    /**
     * Default fuse, in ticks. Three seconds is a release-then-egress window at
     * normal drone speed without letting the charge drift for long.
     */
    public static final int DEFAULT_FUSE_TICKS = 60;
    /**
     * Longest fuse accepted, in ticks. Ten seconds is also the script sleep cap.
     */
    public static final int MAX_FUSE_TICKS = 200;

    /**
     * Smallest accepted drop count.
     */
    public static final int MIN_COUNT = 1;
    /**
     * Default drop count: one charge per call, like one bomb per pass.
     */
    public static final int DEFAULT_COUNT = 1;
    /**
     * Largest accepted drop count. More than a few charges on one pass is an
     * area clear, and the drone has eight slots.
     */
    public static final int MAX_COUNT = 8;

    /**
     * Largest accepted horizontal spread for a multi-charge drop.
     */
    public static final double MAX_SPREAD = 3.0D;

    /**
     * Highest column a release point may be above its impact, in blocks. A
     * charge that has to fall further than this drifts and lands somewhere the
     * caller did not pick.
     */
    public static final int MAX_FALL = 64;

    private TntDropLimits() {
    }

    public static int clampFuse(int ticks) {
        return Math.max(MIN_FUSE_TICKS, Math.min(MAX_FUSE_TICKS, ticks));
    }

    public static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    public static double clampSpread(double spread) {
        return Math.max(0.0D, Math.min(MAX_SPREAD, spread));
    }

    /**
     * Seconds a caller should wait before inspecting the crater.
     */
    public static double fuseSeconds(int fuseTicks) {
        return clampFuse(fuseTicks) / 20.0D;
    }
}
