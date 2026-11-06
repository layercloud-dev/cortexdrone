package ir.layercloud.cortexdrone.action.inventory;

import lombok.Builder;
import lombok.Value;

/** Immutable request data for one inventory action. */
@Value
@Builder
public class InventoryRequest {
    String operation;
    String filter;
    String item;
    int count;
    String playerName;

    String fromType;
    String toType;
    int fromSlot;
    int toSlot;

    boolean hasSource;
    int sourceX;
    int sourceY;
    int sourceZ;

    boolean hasTarget;
    int targetX;
    int targetY;
    int targetZ;

    double radius;
}
