package ir.layercloud.cortexdrone.action.interact;

/**
 * Per-drone interaction state shared by the interaction actions. The
 * {@code InteractionManager} owns its lifetime; actions only read and update it.
 */
public final class InteractionState {
    public int attackCooldown = 0;
}
