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
    boolean broken;
    boolean placed;
    boolean replaced;
    boolean already;
    String block;
    String oldBlock;
    String newBlock;
    int x;
    int y;
    int z;
    int brokenCount;
    java.util.List<String> blocks;

    public static BlockResult failure(String error) {
        return builder().ok(false).error(error).build();
    }

    public static BlockResult broken(String block, int x, int y, int z) {
        return builder()
                .ok(true)
                .broken(true)
                .block(block)
                .brokenCount(1)
                .blocks(java.util.Collections.singletonList(block))
                .x(x)
                .y(y)
                .z(z)
                .build();
    }

    /**
     * A vein break: several blocks gone, with the count and per-block names.
     */
    public static BlockResult vein(String block, int x, int y, int z,
                                   int brokenCount, java.util.List<String> blocks) {
        return builder()
                .ok(true)
                .broken(true)
                .block(block)
                .brokenCount(brokenCount)
                .blocks(blocks)
                .x(x)
                .y(y)
                .z(z)
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

    public static BlockResult replaced(String oldBlock, String newBlock,
                                       int x, int y, int z, boolean already) {
        return builder()
                .ok(true)
                .replaced(true)
                .already(already)
                .oldBlock(oldBlock)
                .newBlock(newBlock)
                .block(newBlock)
                .x(x)
                .y(y)
                .z(z)
                .build();
    }
}
