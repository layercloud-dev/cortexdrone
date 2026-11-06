package ir.layercloud.cortexdrone.service.navigation;

import lombok.Value;

/** One recorded traversal point. */
@Value
public class Breadcrumb {
    int x;
    int y;
    int z;
    boolean skyVisible;
}
