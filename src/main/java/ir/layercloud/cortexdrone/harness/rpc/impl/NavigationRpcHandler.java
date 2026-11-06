package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.BreadcrumbRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.NavigationRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Navigation tools: {@code goto}, {@code stop} and the read-only
 * {@code get_breadcrumbs} trail query.
 */
public final class NavigationRpcHandler implements RpcHandler {
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register("goto", "Fly to a coordinate or standoff; resolves on arrival or failure.")
            .register("stop", "Cancel navigation and hover in place.")
            .register("get_breadcrumbs", "Read the recorded trail and surface anchor.");

    private final NavigationRpcTranslator navigation;
    private final BreadcrumbRpcTranslator breadcrumbs;

    public NavigationRpcHandler(AIDroneManager manager) {
        this.navigation = new NavigationRpcTranslator(manager.getNavigationManager());
        this.breadcrumbs = new BreadcrumbRpcTranslator(manager.getBreadcrumbService());
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
        if ("get_breadcrumbs".equals(m)) return this.breadcrumbs.read(ctrl, args);
        if ("goto".equals(m) || "stop".equals(m)) {
            return this.navigation.handle(ctrl, player, m, args);
        }
        return RpcJson.error("Unsupported navigation tool: " + m);
    }
}
