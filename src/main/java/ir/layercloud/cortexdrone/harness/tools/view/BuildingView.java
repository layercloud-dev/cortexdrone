package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.service.world.VeinLimits;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for block/building actions.
 */
@RequiredArgsConstructor
public final class BuildingView {
    private final BuildingManager service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if ("repair_trail".equals(operation)) {
            BlockResult repaired = this.service.handle(controller, player, operation,
                    BlockRequest.builder().build());
            if (repaired == null) return ToolJson.error("Repair produced no result");
            JsonObject response = ToolJson.ok();
            response.addProperty("repair", repaired.getBlock());
            if (repaired.getError() != null) response.addProperty("note", repaired.getError());
            return response;
        }
        if (!ToolArgs.has(args, "x") || !ToolArgs.has(args, "y") || !ToolArgs.has(args, "z")) {
            return ToolJson.error(operation + " requires x/y/z");
        }
        BlockRequest request;
        if ("place_block".equals(operation) || "replace_block".equals(operation)) {
            String filter = ToolArgs.string(args, "block",
                    ToolArgs.string(args, "with", ToolArgs.string(args, "item", "any")));
            request = BlockRequest.builder()
                    .x(args.get("x").getAsInt())
                    .y(args.get("y").getAsInt())
                    .z(args.get("z").getAsInt())
                    .filter(filter)
                    .meta(ToolArgs.integer(args, "meta", -1))
                    .build();
        } else {
            request = BlockRequest.builder()
                    .x(args.get("x").getAsInt())
                    .y(args.get("y").getAsInt())
                    .z(args.get("z").getAsInt())
                    .vein(ToolArgs.bool(args, "vein", false))
                    .veinRadius(ToolArgs.clampedInt(args, "vein_radius",
                            VeinLimits.DEFAULT_RADIUS, VeinLimits.MIN_RADIUS, VeinLimits.MAX_RADIUS))
                    .veinBlocks(ToolArgs.clampedInt(args, "max_vein_blocks",
                            VeinLimits.DEFAULT_BLOCKS, VeinLimits.MIN_BLOCKS, VeinLimits.MAX_BLOCKS))
                    .build();
        }

        BlockResult result = this.service.handle(controller, player, operation, request);
        if (result == null) return ToolJson.error("Block action produced no result");
        if (!result.isOk()) return ToolJson.error(result.getError());

        JsonObject response = ToolJson.ok();
        response.add("target", ToolJson.point(result.getX(), result.getY(), result.getZ()));
        if ("break_block".equals(operation)) {
            response.addProperty("broken", result.isBroken());
            response.addProperty("broken_count", Math.max(1, result.getBrokenCount()));
            if (request.isVein() && result.getBlocks() != null) {
                com.google.gson.JsonArray blocks = new com.google.gson.JsonArray();
                for (String broken : result.getBlocks()) blocks.add(new com.google.gson.JsonPrimitive(broken));
                response.add("blocks", blocks);
            }
        } else if ("replace_block".equals(operation)) {
            response.addProperty("replaced", result.isReplaced());
            response.addProperty("alreadyMatching", result.isAlready());
            if (result.getOldBlock() != null) response.addProperty("oldBlock", result.getOldBlock());
            if (result.getNewBlock() != null) response.addProperty("newBlock", result.getNewBlock());
        } else {
            response.addProperty("placed", result.isPlaced());
        }
        if (result.getBlock() != null) response.addProperty("block", result.getBlock());
        return response;
    }
}
