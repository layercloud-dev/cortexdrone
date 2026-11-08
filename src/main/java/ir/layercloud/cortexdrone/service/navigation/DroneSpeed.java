package ir.layercloud.cortexdrone.service.navigation;

import ir.layercloud.cortexdrone.config.ModConfig;

/**
 * Drone flight-speed policy.
 *
 * <p>The speed is expressed as a multiplier on the drone's normal cruise tiers,
 * not as a raw velocity, so "1.0" always means exactly the behaviour the
 * navigator was tuned for. Three tiers exist because a close approach must stay
 * slow enough for the arrival radius and the 0.75-wide body:</p>
 *
 * <pre>
 *   near  (goal &lt;= 14 blocks)  0.40 blocks/tick   (8 b/s)
 *   mid   (goal &lt;= 24 blocks)  0.55 blocks/tick  (11 b/s)
 *   far   (goal &gt;  24 blocks)  0.75 blocks/tick  (15 b/s)
 * </pre>
 *
 * <p>{@link #MAX_MULTIPLIER} is the hard ceiling: speeds above it are clamped,
 * never accepted, so a bad config value or a model-supplied parameter cannot
 * turn the drone into an un-collidable projectile. {@link #resolve} is the only
 * place that applies the clamp.</p>
 *
 * <p>A carried passenger never uses a boosted multiplier: the rider's head is
 * 1.8 blocks above the drone, and the extra closing speed makes a low ceiling
 * far more dangerous than the saved travel time is worth.</p>
 */
public final class DroneSpeed {
    /** Base cap for a close approach; tuned for the 0.85 arrival radius. */
    public static final double NEAR = 0.40;
    /** Base cap for a medium leg. */
    public static final double MID = 0.55;
    /** Base cap for a long leg; the fastest the navigator was originally tuned for. */
    public static final double FAR = 0.75;
    /** Distances used to pick a tier, matching TickManager's historical values. */
    public static final double MID_DISTANCE = 14.0;
    public static final double FAR_DISTANCE = 24.0;

    /** The user-facing ceiling: five times the tuned long-range speed. */
    public static final double MAX_MULTIPLIER = 5.0;
    /** Below this the drone would crawl; 0.25 is a quarter of normal speed. */
    public static final double MIN_MULTIPLIER = 0.25;
    /** Default multiplier: exactly the pre-existing speed. */
    public static final double DEFAULT_MULTIPLIER = 1.0;
    /** Replan at least this often in ticks, so A* cannot eat the tick budget. */
    public static final int REPATH_FLOOR = 4;

    /** Absolute velocity ceiling, so no code path can exceed the 5x promise. */
    public static final double HARD_CAP = FAR * MAX_MULTIPLIER;

    private DroneSpeed() {
    }

    /**
     * Base cruise cap for a leg of {@code distance} blocks, before any
     * multiplier is applied.
     */
    public static double baseCap(double distance) {
        if (distance > FAR_DISTANCE) return FAR;
        if (distance > MID_DISTANCE) return MID;
        return NEAR;
    }

    /**
     * Resolves the velocity cap for one tick.
     *
     * @param distance   remaining distance to the final goal
     * @param configured multiplier from the config file
     * @param requested  per-call multiplier from {@code goto}; {@code <= 0}
     *                   means "use the configured one"
     * @param carrying   true when a living entity is riding the drone
     * @return a velocity cap never above {@link #HARD_CAP}
     */
    public static double resolve(double distance, double configured, double requested,
                                 boolean carrying) {
        double multiplier = requested > 0.0 ? requested : configured;
        if (carrying) multiplier = Math.min(multiplier, DEFAULT_MULTIPLIER);
        return clampCap(baseCap(distance) * clampMultiplier(multiplier));
    }

    /** Clamps any multiplier into the supported range. */
    public static double clampMultiplier(double multiplier) {
        if (Double.isNaN(multiplier)) return DEFAULT_MULTIPLIER;
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
    }

    /** Clamps any velocity into the absolute ceiling. */
    public static double clampCap(double cap) {
        if (Double.isNaN(cap)) return NEAR;
        return Math.max(0.0, Math.min(HARD_CAP, cap));
    }

    /**
     * Reads the configured multiplier, falling back to the default when the
     * config is missing or unreadable.
     */
    public static double configured(ModConfig config) {
        if (config == null) return DEFAULT_MULTIPLIER;
        return clampMultiplier(config.getDroneSpeedMultiplier());
    }

    /**
     * Resolves the effective multiplier for one drone tick.
     *
     * <p>A live rider always wins over both the config and the request: a
     * player who boards mid-flight must not inherit a boosted cap, because the
     * extra closing speed is lethal under a low ceiling.</p>
     *
     * @param configured configured multiplier
     * @param requested  per-call multiplier; {@code <= 0} or NaN means "use the config"
     * @param carrying   true when a living entity is riding right now
     */
    public static double effective(double configured, double requested, boolean carrying) {
        if (carrying) return DEFAULT_MULTIPLIER;
        double chosen = requested > 0.0 && !Double.isNaN(requested) ? requested : configured;
        return clampMultiplier(chosen);
    }

    /** Human-readable tier name used in logs and status text. */
    public static String describe(double distance) {
        if (distance > FAR_DISTANCE) return "far";
        if (distance > MID_DISTANCE) return "mid";
        return "near";
    }

    /**
     * Arrival tolerance for the goal at {@code multiplier}.
     *
     * <p>It grows sub-linearly: the scaled braking distance already lets the
     * drone stop inside the tuned radius, so this only absorbs tick
     * discretisation. A linear scale would report "arrived" four blocks away,
     * which breaks precise positioning before break/place/interact.</p>
     */
    public static double arrivalRadius(double multiplier) {
        return 0.85 * Math.sqrt(clampMultiplier(multiplier));
    }

    /** Waypoint-advance tolerance at {@code multiplier}. */
    public static double waypointRadius(double multiplier) {
        return 0.75 * Math.sqrt(clampMultiplier(multiplier));
    }

    /**
     * Ticks between replans. A faster drone covers more ground per tick, so it
     * must correct more often; the floor keeps A* off the tick budget.
     */
    public static int repathTicks(int baseTicks, double multiplier) {
        return Math.max(REPATH_FLOOR,
                (int) Math.round(baseTicks / clampMultiplier(multiplier)));
    }

    /**
     * Braking distance for a cap, mirroring {@code DronePhysicsService}: the
     * ease-in radius is {@code 6 × max(1, cap / 0.75)}.
     */
    public static double brakingRadius(double multiplier) {
        double cap = clampCap(FAR * clampMultiplier(multiplier));
        return 6.0 * Math.max(1.0, cap / 0.75);
    }

    /**
     * Long-range sub-leg stride.
     *
     * <p>The navigator flies toward a point {@code stride} blocks ahead and
     * re-plans when it gets close. At normal speed 16 blocks is well past the
     * 6-block braking distance, so the drone reaches cruise. At 5x the braking
     * distance is ~30 blocks: a 16-block stride made it start braking almost
     * immediately and never cruise, which is why the measured speed stayed near
     * the old baseline. At slow multipliers the opposite happens: a shrunk
     * stride would sit inside the braking zone and the drone would crawl.</p>
     *
     * <p>The invariant is therefore "stride &gt; braking radius" at every
     * supported multiplier, with a margin so the drone actually holds cruise
     * for a while instead of touching it for one tick.</p>
     */
    public static double stride(double multiplier, double baseStride) {
        double scaled = baseStride * clampMultiplier(multiplier);
        return Math.max(scaled, brakingRadius(multiplier) * 1.5);
    }

    /**
     * Distance at which a long-range sub-leg is worth cutting.
     *
     * <p>Sub-legs exist to follow terrain, so a short hop should fly direct.
     * At 5x the drone needs more runway before a stride makes sense.</p>
     */
    public static double strideTrigger(double baseTrigger, double multiplier) {
        return baseTrigger * Math.max(1.0, clampMultiplier(multiplier) * 0.5);
    }
}
