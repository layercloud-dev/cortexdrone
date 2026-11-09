package ir.layercloud.cortexdrone.service.world;

/**
 * The world height envelope every scan, read and planner shares.
 *
 * <p>The values were previously repeated as bare {@code 1}/{@code 2} and
 * {@code 254}/{@code 250} literals in nine classes. A single source makes the
 * tool schema, the scan clamps and the landing rules agree by construction.</p>
 */
public final class WorldBounds {
    private WorldBounds() {
    }

    /** Lowest Y a normal block operation may target. */
    public static final int MIN_Y = 1;

    /** Lowest Y an ore/underground scan may reach (bedrock layer is skipped). */
    public static final int MIN_SCAN_Y = 2;

    /** Highest Y the 1.7.10 world exposes as a normal block. */
    public static final int MAX_Y = 254;

    /** Highest Y a passenger landing may occupy; leaves headroom below the roof. */
    public static final int MAX_LANDING_Y = 250;

    /** Clamps a requested Y into the buildable range. */
    public static int clampY(int y) {
        return Math.max(MIN_Y, Math.min(MAX_Y, y));
    }

    /** True when a Y coordinate is a real block layer in this world. */
    public static boolean inWorld(int y) {
        return y >= MIN_Y && y <= MAX_Y;
    }

    /** Largest footprint edge a site survey will inspect. */
    public static final int MAX_SITE_SIDE = 33;
    /** Largest footprint edge the blueprint planner will generate. */
    public static final int MAX_PLAN_SIDE = 19;
    /** Tallest wall the blueprint planner will generate. */
    public static final int MAX_PLAN_HEIGHT = 12;
    /** Smallest footprint or wall height any planner accepts. */
    public static final int MIN_PLAN_SIDE = 3;
    public static final int MIN_PLAN_HEIGHT = 2;
    /** Smallest headroom a site survey will check. */
    public static final int MIN_SURVEY_HEIGHT = 1;
    /** Largest headroom a site survey will check. */
    public static final int MAX_SURVEY_HEIGHT = 24;

    /** Horizontal world coordinate limit (±30 million in Minecraft). */
    public static final int MAX_COORDINATE = 30_000_000;

    /** Lowest Y a flight waypoint may be clamped to. */
    public static final int MIN_FLIGHT_Y = MIN_SCAN_Y + 1;
    /** Highest Y a flight waypoint may be clamped to. */
    public static final int MAX_FLIGHT_Y = MAX_Y - 1;

    /** Clamps a floating-point flight Y into the flyable envelope. */
    public static double clampFlightY(double y) {
        return Math.max(MIN_FLIGHT_Y, Math.min(MAX_FLIGHT_Y, y));
    }

    /** Clamps an integer dig Y into the diggable envelope. */
    public static int clampDigY(int y) {
        return Math.max(MIN_FLIGHT_Y, Math.min(MAX_FLIGHT_Y, y));
    }

    /** True when a Y coordinate is deep enough to mine or dig. */
    public static boolean diggable(int y) {
        return y > MIN_SCAN_Y && y < MAX_Y;
    }
}
