package ir.layercloud.cortexdrone.action.block;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable result of one block action.
 */
@Value
@Builder
public class BlockResult {
    boolean ok;
    String error;
    boolean started;
    boolean placed;
    String block;
    int x;
    int y;
    int z;

    public static BlockResult failure(String error) {
        return builder().ok(false).error(error).build();
    }

    public static BlockResult breakStarted(boolean started, int x, int y, int z) {
        return builder()
                .ok(started)
                .started(started)
                .x(x)
                .y(y)
                .z(z)
                .error(started ? null : "Could not start single-block break")
                .build();
    }

    public static BlockResult placed(boolean placed, String block, int x, int y, int z) {
        return builder()
                .ok(placed)
                .placed(placed)
                .block(block)
                .x(x)
                .y(y)
                .z(z)
                .error(placed ? null
                        : "Cannot place: no matching block in drone/chest/player inventory or target invalid")
                .build();
    }
}
