package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;

import java.util.*;

/**
 * One generated structure plan: placements, clearing list and material totals.
 *
 * <p>Extracted from {@link StructureArchitect}. A plan is a value object the
 * model reads; it is never executed directly. Tasks are ordered by phase and then
 * by height so a block is never placed in mid-air.</p>
 */
@Getter
@SuppressWarnings("unused")
public class StructurePlan {
    /**
     * Build order: lower phases are placed first.
     */
    public enum Phase {
        FOUNDATION, FLOOR, CORNER, WALL, WINDOW, RAILING, ROOF, DETAIL
    }

    /**
     * Highest number of placement tasks one plan may hold.
     */
    private static final int MAX_TASKS = 6000;

    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final int floorY;
    private final int maxY;
    private final String doorSide;
    private final String style;
    private final String roofStyle;
    private final List<int[]> clearBlocks = new ArrayList<>();
    private final List<BuildTask> buildTasks = new ArrayList<>();
    private final Map<String, Integer> materialCounts = new LinkedHashMap<>();
    private final Map<String, Integer> phaseCounts = new LinkedHashMap<>();
    private int doorX;
    private int doorY;
    private int doorZ;

    public StructurePlan(int x1, int z1, int x2, int z2, int floorY, int maxY,
                         String doorSide, String style, String roofStyle) {
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.floorY = floorY;
        this.maxY = maxY;
        this.doorSide = doorSide;
        this.style = style;
        this.roofStyle = roofStyle;
    }

    public void addTask(int x, int y, int z, String blockFilter, int meta,
                        boolean replace, Phase phase) {
        if (this.buildTasks.size() >= MAX_TASKS) return;
        BuildTask task = new BuildTask(x, y, z, blockFilter, meta, replace,
                phase.name().toLowerCase(Locale.ROOT));
        this.buildTasks.add(task);
        this.materialCounts.merge(task.getBlockFilter(), 1, Integer::sum);
        this.phaseCounts.merge(task.getPhase(), 1, Integer::sum);
    }

    /**
     * Records one voxel that must be cleared before building.
     */
    public void addClearBlock(int x, int y, int z) {
        this.clearBlocks.add(new int[]{x, y, z});
    }

    public void setDoor(int x, int y, int z) {
        this.doorX = x;
        this.doorY = y;
        this.doorZ = z;
    }

    /**
     * Placements ordered so lower phases and lower Y come first.
     */
    public List<BuildTask> orderedTasks() {
        List<BuildTask> ordered = new ArrayList<>(this.buildTasks);
        ordered.sort(Comparator
                .comparingInt(BuildTask::phaseRank)
                .thenComparingInt(BuildTask::getY)
                .thenComparingInt(BuildTask::getX)
                .thenComparingInt(BuildTask::getZ));
        return ordered;
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("x1", this.x1);
        out.addProperty("z1", this.z1);
        out.addProperty("x2", this.x2);
        out.addProperty("z2", this.z2);
        out.addProperty("width", this.x2 - this.x1 + 1);
        out.addProperty("depth", this.z2 - this.z1 + 1);
        out.addProperty("floorY", this.floorY);
        out.addProperty("maxY", this.maxY);
        out.addProperty("doorSide", this.doorSide);
        out.addProperty("style", this.style);
        out.addProperty("roofStyle", this.roofStyle);
        out.addProperty("clearCount", this.clearBlocks.size());
        out.addProperty("buildCount", this.buildTasks.size());

        JsonObject door = new JsonObject();
        door.addProperty("x", this.doorX);
        door.addProperty("y", this.doorY);
        door.addProperty("z", this.doorZ);
        door.addProperty("side", this.doorSide);
        out.add("door", door);

        JsonObject mats = new JsonObject();
        for (Map.Entry<String, Integer> entry : this.materialCounts.entrySet()) {
            mats.addProperty(entry.getKey(), entry.getValue());
        }
        out.add("materialsRequired", mats);
        JsonObject phases = new JsonObject();
        for (Map.Entry<String, Integer> entry : this.phaseCounts.entrySet()) {
            phases.addProperty(entry.getKey(), entry.getValue());
        }
        out.add("phases", phases);

        JsonArray clear = new JsonArray();
        for (int[] point : this.clearBlocks) {
            JsonObject block = new JsonObject();
            block.addProperty("x", point[0]);
            block.addProperty("y", point[1]);
            block.addProperty("z", point[2]);
            clear.add(block);
        }
        out.add("clear", clear);

        JsonArray tasks = new JsonArray();
        for (BuildTask task : orderedTasks()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", task.getX());
            entry.addProperty("y", task.getY());
            entry.addProperty("z", task.getZ());
            entry.addProperty("block", task.getBlockFilter());
            entry.addProperty("phase", task.getPhase());
            if (task.getMeta() >= 0) entry.addProperty("meta", task.getMeta());
            tasks.add(entry);
        }
        out.add("tasks", tasks);
        return out;
    }
}

