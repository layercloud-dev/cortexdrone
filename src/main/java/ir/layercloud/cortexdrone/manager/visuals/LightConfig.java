package ir.layercloud.cortexdrone.manager.visuals;

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
