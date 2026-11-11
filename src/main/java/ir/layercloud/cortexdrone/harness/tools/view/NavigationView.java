package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationResult;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
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
        boolean allowDig = ToolArgs.bool(args, "allowDig", true);
        int maxDigBlocks = ToolArgs.clampedInt(args, "maxDigBlocks", NavigationLimits.DEFAULT_DIG_BLOCKS,
                NavigationLimits.MIN_DIG_BLOCKS, NavigationLimits.MAX_DIG_BLOCKS);
        if (allowDig && maxDigBlocks <= 0) {
            // "allow digging with a zero budget" is contradictory and used to
            // fail every blocked goto with "dig budget 0 exhausted". A caller
            // that passes 0 meant "no digging", so it must also clear allowDig.
            maxDigBlocks = NavigationLimits.DEFAULT_DIG_BLOCKS;
        }
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
                .allowDig(allowDig)
                .maxDigBlocks(maxDigBlocks)
                .recoveryAttempts(ToolArgs.clampedInt(args, "recoveryAttempts",
                        NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS,
                        NavigationLimits.MIN_RECOVERY_ATTEMPTS,
                        NavigationLimits.MAX_RECOVERY_ATTEMPTS))
                // 0 means "use the configured multiplier". DroneSpeed clamps
                // whatever arrives into its supported range.
                .speedMultiplier(ToolArgs.decimal(args, "speed", 0.0D))
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
