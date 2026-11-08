package ir.layercloud.cortexdrone.action.interact;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable request data for one interaction action.
 */
@Value
@Builder
public class InteractionRequest {
    boolean coordinates;
    int x;
    int y;
    int z;

    int side;
    String item;
    Integer slot;
    int entityId;
    boolean entityRequested;

    /**
     * Explicitly accepts an unsafe dismount. Only set by a caller that has
     * already warned the player; the default false keeps riders safe.
     */
    boolean force;

    public boolean hasEntity() {
        return this.entityRequested;
    }

    public boolean hasSlot() {
        return this.slot != null;
    }
}
