package ir.layercloud.cortexdrone.manager.visuals;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-drone visual state owned by {@code VisualsManager}: illumination aura plus
 * the current LED effect/colour. The controller no longer carries these fields.
 */
public final class VisualsState {
    public boolean lightAura = false;
    public int lightRadius = 6;
    public int lightIntensity = 14;
    public int lightColor = 0xFFFFFF;
    public int lightCooldown = 0;
    public long lightAnchor = Long.MIN_VALUE;

    /** Packed world position -> original block-light level, for exact restore. */
    public final Map<Long, Integer> lightCells = new HashMap<>();

    public Integer customLedColor = null;
    public String ledEffect = "NONE";
    public int ledEffectTick = 0;
}
