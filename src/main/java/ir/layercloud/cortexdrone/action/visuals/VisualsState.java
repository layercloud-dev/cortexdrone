package ir.layercloud.cortexdrone.action.visuals;

import ir.layercloud.cortexdrone.service.visuals.VisualLimits;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-drone visual state shared by the visuals actions. The {@code
 * VisualsManager} owns its lifetime; actions only read and update it. LED
 * colour lives on the drone itself; animations are composed by skills.
 */
public final class VisualsState {
    public boolean lightAura = false;
    public int lightRadius = VisualLimits.DEFAULT_RADIUS;
    public int lightIntensity = VisualLimits.DEFAULT_LEVEL;
    public int lightColor = 0xFFFFFF;
    public int lightCooldown = 0;
    public long lightAnchor = Long.MIN_VALUE;

    /** Packed world position -> original block-light level, for exact restore. */
    public final Map<Long, Integer> lightCells = new HashMap<>();
}
