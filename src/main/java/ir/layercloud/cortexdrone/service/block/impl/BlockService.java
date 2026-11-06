package ir.layercloud.cortexdrone.service.block.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.block.impl.BreakBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.PlaceBlockAction;
import ir.layercloud.cortexdrone.context.block.impl.BlockContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import ir.layercloud.cortexdrone.service.building.BuildingService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived block action manager. It only parses requests, creates the matching
 * single-use action, serializes the result and manages context lifetime.
 */
public final class BlockService extends ActionService<BlockContext> {
    private final BuildingService buildingService;

    public BlockService(BuildingService buildingService) {
        super("block", "break_block", "place_block");
        this.buildingService = buildingService;
    }

    @Override
    protected BlockContext createContext(DroneController controller, EntityPlayerMP player,
                                         String operation) {
        return new BlockContext(controller, player, operation);
    }

    @Override
    protected Action<BlockContext> createAction(BlockContext context, String operation,
                                                JsonObject args) {
        if ("break_block".equals(operation)) {
            if (!ActionArgs.has(args, "x") || !ActionArgs.has(args, "y") || !ActionArgs.has(args, "z")) {
                return ignored -> context.result(BlockResult.failure("break_block requires x/y/z"));
            }
            return new BreakBlockAction(this.buildingService, BlockRequest.builder()
                .x(args.get("x").getAsInt())
                .y(args.get("y").getAsInt())
                .z(args.get("z").getAsInt())
                .build());
        }
        if ("place_block".equals(operation)) {
            if (!ActionArgs.has(args, "x") || !ActionArgs.has(args, "y") || !ActionArgs.has(args, "z")) {
                return ignored -> context.result(BlockResult.failure("place_block requires x/y/z"));
            }
            String filter = ActionArgs.string(args, "block",
                ActionArgs.string(args, "with", ActionArgs.string(args, "item", "any")));
            return new PlaceBlockAction(this.buildingService, BlockRequest.builder()
                .x(args.get("x").getAsInt())
                .y(args.get("y").getAsInt())
                .z(args.get("z").getAsInt())
                .filter(filter)
                .meta(ActionArgs.integer(args, "meta", -1))
                .build());
        }
        throw new IllegalArgumentException("Unsupported block action: " + operation);
    }

    @Override
    protected JsonObject serialize(BlockContext context) {
        BlockResult result = context.result();
        if (result == null) return ActionJson.error("Block action produced no result");
        if (!result.isOk()) return ActionJson.error(result.getError());

        JsonObject response = ActionJson.ok();
        response.add("target", ActionJson.point(result.getX(), result.getY(), result.getZ()));
        if ("break_block".equals(context.operation())) {
            response.addProperty("started", result.isStarted());
        } else {
            response.addProperty("placed", result.isPlaced());
            if (result.getBlock() != null) response.addProperty("block", result.getBlock());
        }
        return response;
    }
}
