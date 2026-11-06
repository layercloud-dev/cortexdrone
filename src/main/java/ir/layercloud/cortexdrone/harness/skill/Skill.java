package ir.layercloud.cortexdrone.harness.skill;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, executable workflow.
 *
 * <p>A skill is a small JavaScript program plus retrieval metadata. It is not a
 * tool and not a hidden task: the model still decides when to load it, and the
 * code uses the normal synchronous tool surface. Skills live in
 * {@code config/cortexdrone/skills.json}.</p>
 */
@Data
public class Skill {
    private String name;
    private String description = "";
    private String tags = "";
    private List<String> triggers = new ArrayList<>();
    private String code = "";
    private int version = 1;
    private int uses;
    private long createdAt;
    private long updatedAt;
    private String source = "saved";

    public void touch() {
        long now = System.currentTimeMillis();
        if (this.createdAt <= 0) this.createdAt = now;
        this.updatedAt = now;
    }
}
