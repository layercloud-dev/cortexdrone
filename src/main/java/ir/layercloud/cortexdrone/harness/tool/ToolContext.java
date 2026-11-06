package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.Harness;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Everything a tool handler may need for one call.
 *
 * <p>Tool implementations never reach into global state: the context carries
 * the drone, the invoking player, the loop generation and the harness services.
 * A context with a {@code null} controller is valid for drone-independent
 * tools such as {@code web_search}.</p>
 */
public final class ToolContext {
    private final Harness harness;
    private final DroneController controller;
    private final EntityPlayerMP player;
    private final int loopId;

    public ToolContext(Harness harness, DroneController controller, EntityPlayerMP player, int loopId) {
        this.harness = harness;
        this.controller = controller;
        this.player = player;
        this.loopId = loopId;
    }

    public Harness harness() {
        return this.harness;
    }

    public AIDroneManager manager() {
        return this.harness != null ? this.harness.manager() : null;
    }

    public ModConfig config() {
        return this.harness != null ? this.harness.config() : null;
    }

    public ToolInvoker invoker() {
        return this.harness != null ? this.harness.invoker() : null;
    }

    public JsEngine js() {
        return this.harness != null ? this.harness.js() : null;
    }

    public SkillLibrary skills() {
        return this.harness != null ? this.harness.skills() : null;
    }

    public DroneController controller() {
        return this.controller;
    }

    public EntityPlayerMP player() {
        return this.player;
    }

    public int loopId() {
        return this.loopId;
    }

    /** True when the generation that created this context has been superseded. */
    public boolean cancelled() {
        if (this.controller == null || this.harness == null) return false;
        if (this.controller.drone == null || this.controller.drone.isDead) return true;
        if (this.loopId <= 0) return false;
        return this.harness.sessions().session(this.controller).isLoopCancelled(this.loopId);
    }
}
