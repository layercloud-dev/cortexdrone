package ir.layercloud.cortexdrone.service.world;

import lombok.Data;

/**
 * One no-break volume owned by the player.
 *
 * <p>The live server lost part of its base because a mining task tunnelled into
 * the chamber even though the owner had recorded a 50-block no-dig rule. A
 * memory entry is prose, so the rule could be read but not enforced. This class
 * is the machine-readable form: a vertical cylinder around a named place.</p>
 *
 * <p>A cylinder (not a sphere) is deliberate. The base in the log is a chamber
 * at y=55 with a 3x3 exit shaft that reaches the surface near y=100, so a sphere
 * centred on the floor would stop protecting the shaft halfway up. The
 * horizontal radius bounds the footprint; the vertical radius covers the full
 * shaft.</p>
 */
@Data
public final class ProtectedZone {
    /**
     * Human-readable name shown in refusals and status output.
     */
    private String label = "protected area";
    /**
     * Dimension id the zone belongs to.
     */
    private int dimension = 0;
    private double x;
    private double y;
    private double z;
    /**
     * Horizontal radius in blocks.
     */
    private double radius = 48.0D;
    /**
     * Vertical half-extent in blocks. Defaults to the horizontal radius.
     */
    private double verticalRadius = 48.0D;

    public ProtectedZone() {
    }

    public ProtectedZone(String label, int dimension, double x, double y, double z,
                         double radius, double verticalRadius) {
        this.label = label;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.verticalRadius = verticalRadius;
    }

    /**
     * True when a block coordinate falls inside this zone.
     */
    public boolean contains(int dimensionId, double bx, double by, double bz) {
        if (dimensionId != this.dimension) return false;
        double dx = bx + 0.5D - this.x;
        double dz = bz + 0.5D - this.z;
        double dy = by + 0.5D - this.y;
        double horizontal = this.radius;
        double vertical = this.verticalRadius > 0.0D ? this.verticalRadius : this.radius;
        return dx * dx + dz * dz <= horizontal * horizontal
                && Math.abs(dy) <= vertical;
    }

    /**
     * Repairs a hand-edited or migrated zone.
     */
    public ProtectedZone sanitized() {
        if (this.label == null || this.label.trim().isEmpty()) this.label = "protected area";
        if (Double.isNaN(this.radius) || this.radius <= 0.0D) this.radius = 48.0D;
        this.radius = Math.min(256.0D, this.radius);
        if (Double.isNaN(this.verticalRadius) || this.verticalRadius <= 0.0D) {
            this.verticalRadius = this.radius;
        }
        this.verticalRadius = Math.min(256.0D, this.verticalRadius);
        return this;
    }
}
