package ir.layercloud.cortexdrone.model;

public class BuildTask {
    public final int x;
    public final int y;
    public final int z;
    public String blockFilter;
    public int meta;
    public final boolean replaceExisting;
    public final String targetFilter;

    public BuildTask(int x, int y, int z, String blockFilter, int meta) {
        this(x, y, z, blockFilter, meta, false, null);
    }

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
