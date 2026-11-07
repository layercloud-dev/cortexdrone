package ir.layercloud.cortexdrone.service.world;

import lombok.Getter;
import lombok.ToString;

/**
 * One read-only block placement entry in a generated structure plan. This is a
 * plan value, not a queue item: the AI decides what to place.
 *
 * <p>{@code phase} groups placements for correct building order: foundation,
 * floor, wall, corner, window, roof, railing. The skill places lower phases
 * first so a block never floats in mid-air.</p>
 */
@Getter
@ToString
public class BuildTask {
    public static final String FOUNDATION = "foundation";
    public static final String FLOOR = "floor";
    public static final String WALL = "wall";
    public static final String CORNER = "corner";
    public static final String WINDOW = "window";
    public static final String ROOF = "roof";
    public static final String RAILING = "railing";
    public static final String DETAIL = "detail";

    private final int x;
    private final int y;
    private final int z;
    private final String blockFilter;
    private final int meta;
    private final boolean replaceExisting;
    private final String phase;

    public BuildTask(int x, int y, int z, String blockFilter, int meta, boolean replaceExisting) {
        this(x, y, z, blockFilter, meta, replaceExisting, FLOOR);
    }

    public BuildTask(int x, int y, int z, String blockFilter, int meta,
                     boolean replaceExisting, String phase) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockFilter = (blockFilter == null || blockFilter.isEmpty()) ? "any" : blockFilter;
        this.meta = meta;
        this.replaceExisting = replaceExisting;
        this.phase = phase != null && !phase.isEmpty() ? phase : FLOOR;
    }

    /** Phase rank; lower is placed first. */
    public int phaseRank() {
        if (FOUNDATION.equals(this.phase)) return 0;
        if (FLOOR.equals(this.phase)) return 1;
        if (CORNER.equals(this.phase)) return 2;
        if (WALL.equals(this.phase)) return 3;
        if (WINDOW.equals(this.phase)) return 4;
        if (RAILING.equals(this.phase)) return 5;
        if (ROOF.equals(this.phase)) return 6;
        return 7;
    }
}
