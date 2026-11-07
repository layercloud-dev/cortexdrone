package ir.layercloud.cortexdrone.action.visuals;

import lombok.Builder;
import lombok.Value;

/**
 * Typed lighting configuration request for {@code SetLightAction}.
 */
@Value
@Builder
public class LightConfig {
    Boolean enabled;
    boolean toggle;
    Integer radius;
    Integer level;
    Integer color;
}
