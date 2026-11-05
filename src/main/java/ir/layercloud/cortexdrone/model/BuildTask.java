package ir.layercloud.cortexdrone.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
@EqualsAndHashCode
public class BuildTask {
    private final int x;
    private final int y;
    private final int z;

    @Setter
    private String blockFilter;

    @Setter
    private int meta;

    private final boolean replaceExisting;
    private final String targetFilter;

    public BuildTask(int x, int y, int z, String blockFilter, int meta) {
        this(x, y, z, blockFilter, meta, false, null);
    }

    @Builder
    public BuildTask(int x, int y, int z, String blockFilter, int meta, boolean replaceExisting, String targetFilter) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockFilter = (blockFilter == null || blockFilter.isEmpty()) ? "any" : blockFilter;
        this.meta = meta;
        this.replaceExisting = replaceExisting;
        this.targetFilter = targetFilter;
    }
}
