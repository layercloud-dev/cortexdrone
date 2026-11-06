package ir.layercloud.cortexdrone.manager.navigation;

import lombok.Value;

/**
 * Resolved world position of a navigation goal.
 */
@Value
public class NavigationTarget {
    double x;
    double y;
    double z;
}
