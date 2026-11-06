package ir.layercloud.cortexdrone.action.navigation;

/** One executable movement leg. */
public enum NavigationMode {
    FLIGHT,
    HOLD,
    TRAIL,
    TUNNEL,
    DIG_UP,
    DIG_DOWN,
    STOP
}
