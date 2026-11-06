package ir.layercloud.cortexdrone.harness.memory;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One persistent memory.
 *
 * <p>Facts and preferences have no position. Locations carry a dimension and
 * block/entity coordinates. The same {@code kind + name} pair is updated in
 * place, so "this is my base" twice does not create duplicates.</p>
 */
@Data
public class MemoryEntry {
    private String id;
    private String kind = "fact";
    private String name = "";
    private String text = "";
    private List<String> tags = new ArrayList<>();
    private boolean pinned;
    private String source = "player";
    private int version = 1;

    private boolean hasPosition;
    private double x;
    private double y;
    private double z;
    private int dimension;

    private long createdAt;
    private long updatedAt;

    public void touch() {
        long now = System.currentTimeMillis();
        if (this.createdAt <= 0) this.createdAt = now;
        this.updatedAt = now;
    }

    public String positionSummary() {
        if (!this.hasPosition) return "";
        return "(" + Math.round(this.x) + ", " + Math.round(this.y) + ", " + Math.round(this.z)
                + ", dim " + this.dimension + ")";
    }
}
