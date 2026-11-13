package ir.layercloud.cortexdrone.action.block;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable request data for one block action.
 */
@Value
@Builder
public class BlockRequest {
    int x;
    int y;
    int z;
    String filter;
    int meta;
    /**
     * Break the connected blocks of the same kind, not just the target.
     */
    boolean vein;
    /**
     * Search radius for vein mode, clamped by {@code VeinLimits}.
     */
    int veinRadius;
    /**
     * Hard cap on blocks broken in vein mode, clamped by {@code VeinLimits}.
     */
    int veinBlocks;
}
