package ir.layercloud.cortexdrone.manager.visuals;

import lombok.Builder;
import lombok.Value;

/**
 * Typed lighting state result.
 */
@Value
@Builder
public class LightResult {
    boolean ok;
    String error;
    boolean active;
    int radius;
    int level;
    int color;
    int cells;
    boolean torchFree;

    public static LightResult failure(String error) {
        return builder().ok(false).error(error).build();
    }
}
