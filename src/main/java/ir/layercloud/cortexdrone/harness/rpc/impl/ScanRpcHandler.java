package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.RpcDispatcher;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Transport tool for the time-sliced large block scan: {@code poll_scan}.
 */
public final class ScanRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("poll_scan", "Poll the active time-sliced scan job.");

    private final RpcDispatcher dispatcher;

    public ScanRpcHandler(RpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
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
        if (!"poll_scan".equals(m)) return RpcJson.error("Unsupported scan tool: " + m);
        String jobId = args != null && args.has("jobId") && !args.get("jobId").isJsonNull()
                ? args.get("jobId").getAsString() : null;
        return this.dispatcher.pollScanResult(jobId);
    }
}
