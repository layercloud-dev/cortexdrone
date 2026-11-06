package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON boundary for the goal-driven navigation manager.
 */
@RequiredArgsConstructor
public final class NavigationRpcTranslator {
    private final NavigationManager manager;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        NavigationResult result;
        if ("goto".equals(operation)) {
            result = this.manager.gotoGoal(controller, parseRequest(controller, player, args));
        } else if ("stop".equals(operation)) {
            result = this.manager.stop(controller, player, "RPC stop");
        } else {
            return RpcJson.error("Unsupported navigation operation: " + operation);
        }
        return serialize(result);
    }

    private static NavigationRequest parseRequest(DroneController controller, EntityPlayerMP player,
                                                  JsonObject args) {
        boolean coordinates = RpcArgs.has(args, "x")
                && RpcArgs.has(args, "y")
                && RpcArgs.has(args, "z");
        JsonObject standoff = RpcArgs.object(args, "standoffOf");
        if (standoff == null) standoff = RpcArgs.object(args, "nearBlock");
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
                .build();
    }

    private static JsonObject serialize(NavigationResult result) {
        if (result == null) return RpcJson.error("Navigation produced no result");
        if (!result.isOk()) return RpcJson.error(result.getError());
        JsonObject response = RpcJson.ok();
        response.addProperty("started", result.isStarted());
        response.addProperty("arrived", result.isArrived());
        response.addProperty("phase", result.getPhase());
        JsonObject goal = new JsonObject();
        goal.addProperty("x", Math.round(result.getGoalX() * 100.0) / 100.0);
        goal.addProperty("y", Math.round(result.getGoalY() * 100.0) / 100.0);
        goal.addProperty("z", Math.round(result.getGoalZ() * 100.0) / 100.0);
        response.add("goal", goal);
        return response;
    }
}
