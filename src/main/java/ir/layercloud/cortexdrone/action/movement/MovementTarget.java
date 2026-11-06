package ir.layercloud.cortexdrone.action.movement;

import lombok.Value;

/** Immutable world position used by movement actions. */
@Value
public class MovementTarget {
    double x;
    double y;
    double z;
}
