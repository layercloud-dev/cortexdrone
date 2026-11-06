package ir.layercloud.cortexdrone.action.block;

import lombok.Builder;
import lombok.Value;

/** Immutable request data for one block action. */
@Value
@Builder
public class BlockRequest {
    int x;
    int y;
    int z;
    String filter;
    int meta;
}
