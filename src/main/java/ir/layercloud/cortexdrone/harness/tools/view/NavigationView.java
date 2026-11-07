package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON boundary for the goal-driven navigation manager.
 */
@RequiredArgsConstructor
public final class NavigationView {
    private final NavigationManager manager;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        NavigationResult result;
        if ("goto".equals(operation)) {
            result = this.manager.gotoGoal(controller, parseRequest(controller, player, args));
        } else if ("stop".equals(operation)) {
            result = this.manager.stop(controller, player, "tool stop");
        } else {
            return ToolJson.error("Unsupported navigation operation: " + operation);
        }
        return serialize(result);
    }

    private static NavigationRequest parseRequest(DroneController controller, EntityPlayerMP player,
                                                  JsonObject args) {
        boolean coordinates = ToolArgs.has(args, "x")
                && ToolArgs.has(args, "y")
                && ToolArgs.has(args, "z");
        JsonObject standoff = ToolArgs.object(args, "standoffOf");
        if (standoff == null) standoff = ToolArgs.object(args, "nearBlock");
        boolean hasStandoff = standoff != null
                && standoff.has("x") && standoff.has("y") && standoff.has("z");
        return NavigationRequest.builder()
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
                .allowDig(ToolArgs.bool(args, "allowDig", true))
                .maxDigBlocks(ToolArgs.clampedInt(args, "maxDigBlocks", 128, 0, 512))
                .recoveryAttempts(ToolArgs.clampedInt(args, "recoveryAttempts", 12, 0, 32))
                .build();
    }

    private static JsonObject serialize(NavigationResult result) {
        if (result == null) return ToolJson.error("Navigation produced no result");
        if (!result.isOk()) return ToolJson.error(result.getError());
        JsonObject response = ToolJson.ok();
        response.addProperty("started", result.isStarted());
        response.addProperty("arrived", result.isArrived());
        response.addProperty("phase", result.getPhase());
        if (result.getRequestId() > 0L) {
            response.addProperty("request_id", result.getRequestId());
        }
        JsonObject goal = new JsonObject();
        goal.addProperty("x", Math.round(result.getGoalX() * 100.0) / 100.0);
        goal.addProperty("y", Math.round(result.getGoalY() * 100.0) / 100.0);
        goal.addProperty("z", Math.round(result.getGoalZ() * 100.0) / 100.0);
        response.add("goal", goal);
        return response;
    }
}
