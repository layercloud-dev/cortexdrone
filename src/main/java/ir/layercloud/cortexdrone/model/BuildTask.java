package ir.layercloud.cortexdrone.model;

import lombok.Getter;
import lombok.ToString;

/**
 * One read-only block placement entry in a generated structure plan. This is a
 * plan value, not a queue item: the AI decides what to place.
 */
@Getter
@ToString
public class BuildTask {
    private final int x;
    private final int y;
    private final int z;
    private final String blockFilter;
    private final int meta;
    private final boolean replaceExisting;

    public BuildTask(int x, int y, int z, String blockFilter, int meta, boolean replaceExisting) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockFilter = (blockFilter == null || blockFilter.isEmpty()) ? "any" : blockFilter;
        this.meta = meta;
        this.replaceExisting = replaceExisting;
    }
}
