package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * JSON boundary for the read-only breadcrumb service.
 */
@RequiredArgsConstructor
public final class BreadcrumbView {
    private final BreadcrumbService service;

    public JsonObject read(DroneController controller, JsonObject args) {
        if (controller == null) return ToolJson.error("No active drone");
        int limit = ToolArgs.integer(args, "limit", 64);
        List<Breadcrumb> page = this.service.snapshot(controller, limit);
        JsonObject response = ToolJson.ok();
        JsonArray breadcrumbs = new JsonArray();
        for (Breadcrumb point : page) {
            JsonObject crumb = new JsonObject();
            crumb.addProperty("x", point.getX());
            crumb.addProperty("y", point.getY());
            crumb.addProperty("z", point.getZ());
            crumb.addProperty("skyVisible", point.isSkyVisible());
            breadcrumbs.add(crumb);
        }
        Breadcrumb anchor = this.service.surfaceAnchor(controller);
        if (anchor != null) {
            JsonObject surface = new JsonObject();
            surface.addProperty("x", anchor.getX());
            surface.addProperty("y", anchor.getY());
            surface.addProperty("z", anchor.getZ());
            response.add("lastSurfaceAnchor", surface);
        }
        response.addProperty("count", this.service.count(controller));
        response.add("breadcrumbs", breadcrumbs);
        return response;
    }
}
