package ir.layercloud.cortexdrone.action.movement.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.ActionService;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived movement service. It parses the master request, creates one
 * single-use movement action, executes it and owns the resulting navigation
 * context until the navigator finishes.
 */
public final class MovementService extends ActionService<MovementContext> {
    private final AIDroneManager manager;

    public MovementService(AIDroneManager manager) {
        super("movement", "navigate", "tunnel", "digup", "digdown", "get_breadcrumbs");
        this.manager = manager;
    }

    @Override
    protected MovementContext createContext(DroneController controller, EntityPlayerMP player,
                                            String operation) {
        return new MovementContext(controller, player, operation);
    }

    @Override
    protected Action<MovementContext> createAction(MovementContext context, String operation,
                                                   JsonObject args) {
        MovementRequest request = parse(context.controller(), context.player(), args);
        if ("navigate".equals(operation)) return new NavigateAction(this.manager, request);
        if ("tunnel".equals(operation)) return new TunnelAction(this.manager, request);
        if ("digup".equals(operation)) return new DigUpAction(this.manager, request);
        if ("digdown".equals(operation)) return new DigDownAction(this.manager, request);
        if ("get_breadcrumbs".equals(operation)) return new BreadcrumbsAction(request);
        throw new IllegalArgumentException("Unsupported movement action: " + operation);
    }

    @Override
    protected JsonObject serialize(MovementContext context) {
        MovementResult result = context.result();
        if (result == null) return ActionJson.error("Movement action produced no result");
        if (!result.isOk()) return ActionJson.error(result.getError());
        if (result.hasBreadcrumbs()) return serializeBreadcrumbs(result);

        JsonObject response = ActionJson.ok();
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
        JsonObject response = ActionJson.ok();
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

    private static MovementRequest parse(DroneController controller, EntityPlayerMP player,
                                         JsonObject args) {
        boolean coordinates = ActionArgs.has(args, "x")
            && ActionArgs.has(args, "y")
            && ActionArgs.has(args, "z");

        JsonObject standoff = ActionArgs.object(args, "standoffOf");
        if (standoff == null) standoff = ActionArgs.object(args, "nearBlock");
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
            .route(ActionArgs.string(args, "route", null))
            .depth(ActionArgs.integer(args, "depth", 8))
            .limit(ActionArgs.integer(args, "limit", 64))
            .build();
    }
}
