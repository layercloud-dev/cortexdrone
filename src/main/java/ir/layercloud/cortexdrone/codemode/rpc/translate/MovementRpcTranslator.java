package ir.layercloud.cortexdrone.codemode.rpc.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/** JSON translation boundary for movement actions. */
@RequiredArgsConstructor
public final class MovementRpcTranslator {
    private final MovementService service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        MovementResult result = this.service.handle(
            controller, player, operation, parse(controller, player, args));
        return serialize(result);
    }

    private static MovementRequest parse(DroneController controller, EntityPlayerMP player,
                                         JsonObject args) {
        boolean coordinates = RpcArgs.has(args, "x")
            && RpcArgs.has(args, "y")
            && RpcArgs.has(args, "z");
        JsonObject standoff = RpcArgs.object(args, "standoffOf");
        if (standoff == null) standoff = RpcArgs.object(args, "nearBlock");
        boolean hasStandoff = standoff != null
            && standoff.has("x") && standoff.has("y") && standoff.has("z");
        return MovementRequest.builder()
            .controller(controller)
            .player(player)
            .coordinates(coordinates)
            .x(coordinates ? args.get("x").getAsDouble() : 0.0)
            .y(coordinates ? args.get("y").getAsDouble() : 0.0)
            .z(coordinates ? args.get("z").getAsDouble() : 0.0)
            .standoff(hasStandoff)
            .standoffX(hasStandoff ? standoff.get("x").getAsInt() : 0)
            .standoffY(hasStandoff ? standoff.get("y").getAsInt() : 0)
            .standoffZ(hasStandoff ? standoff.get("z").getAsInt() : 0)
            .route(MovementRoute.parse(RpcArgs.string(args, "route", null), MovementRoute.AUTO))
            .depth(RpcArgs.integer(args, "depth", 8))
            .limit(RpcArgs.integer(args, "limit", 64))
            .build();
    }

    private static JsonObject serialize(MovementResult result) {
        if (result == null) return RpcJson.error("Movement action produced no result");
        if (!result.isOk()) return RpcJson.error(result.getError());
        if (result.hasBreadcrumbs()) return serializeBreadcrumbs(result);

        JsonObject response = RpcJson.ok();
        response.addProperty("route", result.getRoute());
        response.addProperty("viaSurface", result.isViaSurface());
        JsonObject goal = new JsonObject();
        goal.addProperty("x", Math.round(result.getGoalX() * 100.0) / 100.0);
        goal.addProperty("y", Math.round(result.getGoalY() * 100.0) / 100.0);
        goal.addProperty("z", Math.round(result.getGoalZ() * 100.0) / 100.0);
        response.add("goal", goal);
        return response;
    }

    private static JsonObject serializeBreadcrumbs(MovementResult result) {
        JsonObject response = RpcJson.ok();
        JsonArray breadcrumbs = new JsonArray();
        List<int[]> page = result.getBreadcrumbs();
        for (int[] point : page) {
            JsonObject crumb = new JsonObject();
            crumb.addProperty("x", point[0]);
            crumb.addProperty("y", point[1]);
            crumb.addProperty("z", point[2]);
            crumb.addProperty("skyVisible", point[3] == 1);
            breadcrumbs.add(crumb);
        }
        int[] anchor = result.getLastSurfaceAnchor();
        if (anchor != null) {
            JsonObject surface = new JsonObject();
            surface.addProperty("x", anchor[0]);
            surface.addProperty("y", anchor[1]);
            surface.addProperty("z", anchor[2]);
            response.add("lastSurfaceAnchor", surface);
        }
        response.addProperty("count", result.getBreadcrumbCount());
        response.add("breadcrumbs", breadcrumbs);
        return response;
    }
}
