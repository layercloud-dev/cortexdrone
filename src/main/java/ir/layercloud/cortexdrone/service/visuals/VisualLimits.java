package ir.layercloud.cortexdrone.service.visuals;

/**
 * Illumination-aura bounds shared by the tool schema, the state defaults and
 * the aura applier.
 *
 * <p>Regression: {@code set_light} advertised radius 1..32 / level 0..15 while
 * {@code LightingAura} silently clamped to 2..12 / 4..15, so a caller could not
 * tell a cap from a request.</p>
 */
public final class VisualLimits {
    private VisualLimits() {
    }

    /**
     * Smallest radius the aura applies.
     */
    public static final int MIN_RADIUS = 2;
    /**
     * Largest radius the aura applies.
     */
    public static final int MAX_RADIUS = 12;
    /**
     * Radius used when the caller names none.
     */
    public static final int DEFAULT_RADIUS = 6;

    /**
     * Smallest light level the aura applies.
     */
    public static final int MIN_LEVEL = 4;
    /**
     * Largest light level the aura applies.
     */
    public static final int MAX_LEVEL = 15;
    /**
     * Light level used when the caller names none.
     */
    public static final int DEFAULT_LEVEL = 14;

    /**
     * Largest RGB component accepted by the colour prose.
     */
    public static final int MAX_COLOR = 255;

    /**
     * Particle burst bounds and default.
     */
    public static final int MIN_PARTICLES = 1;
    public static final int MAX_PARTICLES = 80;
    public static final int DEFAULT_PARTICLES = 12;
    /**
     * Sound volume bounds and default.
     */
    public static final double MIN_VOLUME = 0.0;
    public static final double MAX_VOLUME = 4.0;
    public static final double DEFAULT_VOLUME = 1.0;
    /**
     * Sound pitch bounds and default.
     */
    public static final double MIN_PITCH = 0.5;
    public static final double MAX_PITCH = 2.0;
    public static final double DEFAULT_PITCH = 1.0;
    /**
     * Note semitone bounds and default.
     */
    public static final int MIN_SEMITONE = 0;
    public static final int MAX_SEMITONE = 24;
    public static final int DEFAULT_SEMITONE = 12;

    /**
     * Clamps a requested aura radius into the applied range.
     */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /**
     * Clamps a requested light level into the applied range.
     */
    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }
}
