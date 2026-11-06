package ir.layercloud.cortexdrone.codemode.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.interact.impl.InteractionService;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/** JSON translation boundary for interaction actions. */
@RequiredArgsConstructor
public final class InteractionRpcTranslator {
    private final InteractionService service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        InteractionResult result = this.service.handle(
            controller, player, operation, parse(args));
        return serialize(result);
    }

    private static InteractionRequest parse(JsonObject args) {
        boolean coordinates = RpcArgs.has(args, "x")
            && RpcArgs.has(args, "y")
            && RpcArgs.has(args, "z");
        boolean entityRequested = RpcArgs.has(args, "entityId") || RpcArgs.has(args, "attack");
        int entityId = -1;
        if (entityRequested) {
            try {
                entityId = RpcArgs.integer(args, "entityId",
                    RpcArgs.integer(args, "attack", -1));
            } catch (RuntimeException ignored) {
                entityId = -1;
            }
        }
        return InteractionRequest.builder()
            .coordinates(coordinates)
            .x(coordinates ? args.get("x").getAsInt() : 0)
            .y(coordinates ? args.get("y").getAsInt() : 0)
            .z(coordinates ? args.get("z").getAsInt() : 0)
            .side(RpcArgs.integer(args, "side", 1))
            .item(RpcArgs.string(args, "item", null))
            .slot(RpcArgs.has(args, "slot")
                ? Integer.valueOf(args.get("slot").getAsInt()) : null)
            .entityId(entityId)
            .entityRequested(entityRequested)
            .build();
    }

    private static JsonObject serialize(InteractionResult result) {
        if (result == null) return RpcJson.error("Interaction action produced no result");
        JsonObject response = result.isOk()
            ? RpcJson.ok()
            : RpcJson.error(result.getError());
        RpcJson.addAll(response, result.getFields());
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
