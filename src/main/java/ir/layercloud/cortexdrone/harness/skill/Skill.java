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
 * {@code config/cortexdrone/skills.json}; bundled skills are resources.</p>
 *
 * <p>Skills compose: one skill can call another with {@code skills.call(name,
 * params)} and declare that dependency in {@link #requires}. This keeps each
 * workflow small instead of duplicating mining, delivery or farming logic.</p>
 */
@Data
public class Skill {
    private String name;
    private String description = "";
    private String tags = "";
    private List<String> triggers = new ArrayList<>();
    /** Other skills this one calls through {@code skills.call}. */
    private List<String> requires = new ArrayList<>();
    /** Bundled skills may keep their JavaScript in a sibling resource file. */
    private String codeFile;
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
