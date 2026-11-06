package ir.layercloud.cortexdrone.service.effect;

import lombok.Builder;
import lombok.Value;

/** Typed lighting configuration request. */
@Value
@Builder
public class LightConfig {
    Boolean enabled;
    boolean toggle;
    Integer radius;
    Integer level;
    Integer color;
}
