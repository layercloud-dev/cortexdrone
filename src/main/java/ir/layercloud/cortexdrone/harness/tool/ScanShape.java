package ir.layercloud.cortexdrone.harness.tool;

/**
 * Volume shape used by block scans.
 *
 * <p>Parsed once per scan instead of re-normalising a string inside the
 * innermost voxel loop, and shared by the live and time-sliced scan paths so
 * they cannot disagree about what {@code shell} means.</p>
 */
public enum ScanShape {
    CUBE,
    SPHERE,
    CYLINDER,
    SHELL;

    /** Parses a user-supplied shape name; unknown names fall back to {@link #CUBE}. */
    public static ScanShape parse(String raw) {
        if (raw == null) return CUBE;
        String kind = raw.trim().toLowerCase();
        if (kind.isEmpty() || "cube".equals(kind) || "box".equals(kind)) return CUBE;
        if ("sphere".equals(kind) || "ball".equals(kind)) return SPHERE;
        if ("cylinder".equals(kind) || "column".equals(kind)) return CYLINDER;
        if ("shell".equals(kind) || "surface".equals(kind)) return SHELL;
        return CUBE;
    }

    /** True when the offset lies inside this shape for the given radius. */
    public boolean contains(int dx, int dy, int dz, int radius) {
        switch (this) {
            case SPHERE:
                return dx * dx + dy * dy + dz * dz <= radius * radius;
            case CYLINDER:
                return dx * dx + dz * dz <= radius * radius;
            case SHELL: {
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                return distance > radius - 2.5 && distance <= radius;
            }
            case CUBE:
            default:
                return true;
        }
    }
}
