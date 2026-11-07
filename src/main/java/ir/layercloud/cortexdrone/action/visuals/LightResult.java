package ir.layercloud.cortexdrone.action.visuals;

import lombok.Builder;
import lombok.Value;

/**
 * Typed lighting state result of {@code SetLightAction}.
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
