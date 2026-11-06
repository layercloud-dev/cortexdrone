package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.manager.interact.impl.InteractionManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for interaction actions.
 */
@RequiredArgsConstructor
public final class InteractionView {
    private final InteractionManager service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        InteractionResult result = this.service.handle(
                controller, player, operation, parse(args));
        return serialize(result);
    }

    private static InteractionRequest parse(JsonObject args) {
        boolean coordinates = ToolArgs.has(args, "x")
                && ToolArgs.has(args, "y")
                && ToolArgs.has(args, "z");
        boolean entityRequested = ToolArgs.has(args, "entityId") || ToolArgs.has(args, "attack");
        int entityId = -1;
        if (entityRequested) {
            try {
                entityId = ToolArgs.integer(args, "entityId",
                        ToolArgs.integer(args, "attack", -1));
            } catch (RuntimeException ignored) {
                // Malformed entity id: keep the -1 default.
            }
        }
        return InteractionRequest.builder()
                .coordinates(coordinates)
                .x(coordinates ? args.get("x").getAsInt() : 0)
                .y(coordinates ? args.get("y").getAsInt() : 0)
                .z(coordinates ? args.get("z").getAsInt() : 0)
                .side(ToolArgs.integer(args, "side", 1))
                .item(ToolArgs.string(args, "item", null))
                .slot(ToolArgs.has(args, "slot")
                        ? args.get("slot").getAsInt() : null)
                .entityId(entityId)
                .entityRequested(entityRequested)
                .build();
    }

    private static JsonObject serialize(InteractionResult result) {
        if (result == null) return ToolJson.error("Interaction action produced no result");
        JsonObject response = result.isOk()
                ? ToolJson.ok()
                : ToolJson.error(result.getError());
        ToolJson.addAll(response, result.getFields());
        if (result.getStandoff() != null) {
            JsonObject standoff = new JsonObject();
            standoff.addProperty("x", Math.round(result.getStandoff()[0] * 100.0) / 100.0);
            standoff.addProperty("y", Math.round(result.getStandoff()[1] * 100.0) / 100.0);
            standoff.addProperty("z", Math.round(result.getStandoff()[2] * 100.0) / 100.0);
            response.add("standoff", standoff);
        }
        return response;
    }
}
