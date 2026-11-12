package ir.layercloud.cortexdrone.service.navigation;

/**
 * Tuning constants for the per-tick navigation decision.
 *
 * <p>These were inline literals in {@code NavigateAction.tick} (6.0, 20.0,
 * 24.0, 18.0, 32.0, 16.0, 4.5, 0.85, 0.90, 12, 999.0, ...). A single owner keeps
 * the stride, surface-detour and repath thresholds consistent and reviewable.</p>
 */
public final class NavigationTuning {
    private NavigationTuning() {
    }

    /**
     * Vertical gap that makes a sheltered goal trigger a surface detour.
     */
    public static final double SURFACE_DETOUR_HEIGHT = 6.0;
    /**
     * Horizontal distance that makes a sheltered goal trigger a surface detour.
     */
    public static final double SURFACE_DETOUR_DISTANCE = 20.0;
    /**
     * Largest distance to a usable surface anchor.
     */
    public static final double SURFACE_ANCHOR_RADIUS = 24.0;
    /**
     * Vertical tolerance for treating a goal as on the surface.
     */
    public static final int SURFACE_TOLERANCE = 3;

    /**
     * Stride trigger distance under open sky.
     */
    public static final double STRIDE_TRIGGER_OPEN_SKY = 18.0;
    /**
     * Stride trigger distance under a roof.
     */
    public static final double STRIDE_TRIGGER_SHELTERED = 32.0;
    /**
     * Stride length under open sky.
     */
    public static final double STRIDE_STEP_OPEN_SKY = 16.0;
    /**
     * Stride length under a roof.
     */
    public static final double STRIDE_STEP_SHELTERED = 24.0;
    /**
     * Extra clearance above terrain for an open-sky stride.
     */
    public static final double STRIDE_CLEARANCE = 4.5;

    /**
     * Sentinel for "no path goal recorded yet".
     */
    public static final double NO_GOAL_SENTINEL = 999.0;
    /**
     * Goal drift that triggers a repath.
     */
    public static final double REPATH_DISTANCE = 0.85;
    /**
     * Waypoint drift that triggers a retarget.
     */
    public static final double WAYPOINT_RETARGET_DISTANCE = 0.90;
    /**
     * Ticks stuck before a forced repath.
     */
    public static final int STUCK_REPATH_TICKS = 12;
    /**
     * Distance below which the short repath cooldown is used.
     */
    public static final double REPATH_NEAR_DISTANCE = 18.0;
    /**
     * Repath cooldown for a short leg, in ticks.
     */
    public static final int REPATH_COOLDOWN_NEAR = 8;
    /**
     * Repath cooldown for a long leg, in ticks.
     */
    public static final int REPATH_COOLDOWN_FAR = 12;

    /**
     * Height offset for a surface-anchor target.
     */
    public static final double ANCHOR_HEIGHT_OFFSET = 1.0;
    /**
     * Height above the current column for the emergency escape target.
     */
    public static final double ESCAPE_HEIGHT_OFFSET = 2.5;
    /**
     * Target delta used to command a full stop.
     */
    public static final float STOP_DELTA = 0.05f;
    /**
     * Velocity damping applied when the active waypoint is blocked.
     */
    public static final double BRAKE_DAMPING = 0.3;
    /**
     * Stuck ticks recorded when the active waypoint is blocked.
     */
    public static final int BLOCKED_STUCK_TICKS = 6;
    /**
     * Target delta used for a normal waypoint.
     */
    public static final float WAYPOINT_DELTA = 0.1f;
    /**
     * Terrain sampling step along a high-speed stride.
     */
    public static final double STRIDE_SAMPLE_STEP = 8.0;
    /**
     * Body height above the feet used by the passability check.
     */
    public static final int BODY_HEIGHT = 1;

    /**
     * Session-level progress watchdog. A leg watchdog stops one wedged leg; a
     * long run of legs that never gets closer to the goal used to burn the
     * entire 96-recovery budget (the clay task spent 13 minutes cycling
     * detours in a forest). This is the outer guard: 90 seconds without
     * approaching the goal ends the request honestly.
     */
    public static final int NO_PROGRESS_LIMIT_TICKS = 1800;
    /**
     * Distance improvement that resets the session-level watchdog, in blocks.
     */
    public static final double NO_PROGRESS_EPSILON = 0.75D;
    /**
     * Failed legs before recovery tries a climb above the local canopy.
     */
    public static final int CANOPY_ESCAPE_MIN_FAILURES = 2;
    /**
     * A drone this close under the local terrain top is in a canopy, not a cave.
     */
    public static final int CANOPY_SCAN_HEIGHT = 24;
    /**
     * Extra height above the canopy for the climb-out leg.
     */
    public static final double CANOPY_CLEARANCE = 5.0D;
}
