package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.StateView;

import java.util.Arrays;
import java.util.List;

/**
 * Session tools: live state and cancellation.
 *
 * <p>These are the only tools that operate on the task generation itself. They
 * never start work; they expose the truth and stop everything safely.</p>
 */
public final class SessionTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("state")
                    .description("Return a live snapshot of the drone: position, dimension, energy, "
                            + "inventory, task status/notes, navigation phase and error, active actions, "
                            + "light aura and LED. Read-only and cheap; call it whenever you need current "
                            + "values instead of assuming the task-start context is still true.")
                    .parameters(ToolSchema.object())
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(3_000L)
                    .build(),
            ToolSpec.builder("cancel")
                    .description("Cancel the current task generation, navigation and every retained action, "
                            + "then hover in place. Use only when the player asked to stop or when the task "
                            + "is provably impossible. The current model loop is also superseded.")
                    .parameters(ToolSchema.object()
                            .optionalString("reason", "Short human-readable reason recorded in the log.", "model"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build()
    );

    private final AIDroneManager manager;
    private final StateView status;

    public SessionTools(AIDroneManager manager) {
        this.manager = manager;
        this.status = new StateView(manager);
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("state", getClass().getSimpleName(),
                (context, arguments) -> this.status.status(context.controller(), arguments));
        registry.bind("cancel", getClass().getSimpleName(),
                (context, arguments) -> cancel(context.controller(), context.loopId(), arguments));
    }

    private JsonObject cancel(DroneController controller, int loopId, JsonObject args) {
        if (controller == null) return ToolJson.error("no_drone", "No active drone.", null, false);
        DroneSession session = this.manager.getSessionManager().session(controller);
        int cancelled = session.cancelOtherLoops(loopId, "model_cancel");
        this.manager.clearActionState(controller);
        session.cancel();
        this.manager.getNavigationManager().stop(controller, null, "cancel");
        String reason = args != null && args.has("reason") ? args.get("reason").getAsString() : "model";
        this.manager.getLogger().log("TASK", controller, "Cancelled by model tool", "reason=" + reason);
        JsonObject result = ToolJson.ok();
        result.addProperty("cancelled", true);
        result.addProperty("cancelled_loops", cancelled);
        return result;
    }
}
