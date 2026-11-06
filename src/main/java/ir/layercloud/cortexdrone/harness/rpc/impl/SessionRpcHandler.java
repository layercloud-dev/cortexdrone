package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import ir.layercloud.cortexdrone.harness.rpc.translate.StateRpcTranslator;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Task/session tools: {@code state} and {@code cancel}.
 */
public final class SessionRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("state", "Live drone, task, navigation, lighting and inventory snapshot.")
            .register("cancel", "Cancel the current task, loops and retained actions.");

    private final AIDroneManager manager;
    private final StateRpcTranslator status;

    public SessionRpcHandler(AIDroneManager manager) {
        this.manager = manager;
        this.status = new StateRpcTranslator(manager);
    }

    /** The tools this handler owns, available without a live manager. */
    public static ToolRegistry tools() {
        return TOOLS;
    }

    @Override
    public ToolRegistry toolRegistry() {
        return TOOLS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if ("state".equals(m)) return this.status.status(ctrl, args);
        if ("cancel".equals(m)) return cancel(ctrl, args);
        return RpcJson.error("Unsupported session tool: " + m);
    }

    private JsonObject cancel(DroneController ctrl, JsonObject args) {
        if (ctrl == null) return RpcJson.error("No active drone");
        int callerLoopId = args != null && args.has("loopId") ? args.get("loopId").getAsInt() : 0;
        DroneSession session = this.manager.getSessionManager().session(ctrl);
        int cancelled = session.cancelOtherLoops(callerLoopId);
        this.manager.clearActionState(ctrl);
        session.cancel();
        this.manager.getNavigationManager().stop(ctrl, null, "cancel");
        JsonObject res = RpcJson.ok();
        res.addProperty("cancelled", true);
        res.addProperty("cancelledLoops", cancelled);
        return res;
    }
}
