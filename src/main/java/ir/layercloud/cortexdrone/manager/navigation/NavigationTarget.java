package ir.layercloud.cortexdrone.manager.navigation;

import lombok.Value;

/**
 * Resolved world position of a navigation goal.
 *
 * <p>{@code blockApproach} means the point is the centre of a block the caller
 * wants to stand beside, not an open standoff. Navigation then tunnels toward
 * it and stops as soon as the block is visible and within reach.</p>
 */
@Value
public class NavigationTarget {
    double x;
    double y;
    double z;
    boolean blockApproach;

    public NavigationTarget(double x, double y, double z) {
        this(x, y, z, false);
    }

    public NavigationTarget(double x, double y, double z, boolean blockApproach) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockApproach = blockApproach;
    }
}
