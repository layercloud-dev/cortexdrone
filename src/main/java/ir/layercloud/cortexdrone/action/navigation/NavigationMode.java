package ir.layercloud.cortexdrone.action.navigation;

/**
 * One executable movement leg. Dig/tunnel legs do not exist: navigation never
 * breaks blocks, the model composes digging with break_block.
 */
public enum NavigationMode {
    FLIGHT,
    STOP
}
