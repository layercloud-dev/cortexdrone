package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for block/building actions.
 */
@RequiredArgsConstructor
public final class BuildingRpcTranslator {
    private final BuildingManager service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if (!RpcArgs.has(args, "x") || !RpcArgs.has(args, "y") || !RpcArgs.has(args, "z")) {
            return RpcJson.error(operation + " requires x/y/z");
        }
        BlockRequest request;
        if ("place_block".equals(operation)) {
            String filter = RpcArgs.string(args, "block",
                    RpcArgs.string(args, "with", RpcArgs.string(args, "item", "any")));
            request = BlockRequest.builder()
                    .x(args.get("x").getAsInt())
                    .y(args.get("y").getAsInt())
                    .z(args.get("z").getAsInt())
                    .filter(filter)
                    .meta(RpcArgs.integer(args, "meta", -1))
                    .build();
        } else {
            request = BlockRequest.builder()
                    .x(args.get("x").getAsInt())
                    .y(args.get("y").getAsInt())
                    .z(args.get("z").getAsInt())
                    .build();
        }

        BlockResult result = this.service.handle(controller, player, operation, request);
        if (result == null) return RpcJson.error("Block action produced no result");
        if (!result.isOk()) return RpcJson.error(result.getError());

        JsonObject response = RpcJson.ok();
        response.add("target", RpcJson.point(result.getX(), result.getY(), result.getZ()));
        if ("break_block".equals(operation)) {
            response.addProperty("broken", result.isBroken());
            if (result.getBlock() != null) response.addProperty("block", result.getBlock());
        } else {
            response.addProperty("placed", result.isPlaced());
            if (result.getBlock() != null) response.addProperty("block", result.getBlock());
        }
        return response;
    }
}
