package ir.layercloud.cortexdrone.service.interact.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.impl.AttackEntityAction;
import ir.layercloud.cortexdrone.action.interact.impl.InteractBlockAction;
import ir.layercloud.cortexdrone.action.interact.impl.UnpickEntityAction;
import ir.layercloud.cortexdrone.action.interact.impl.PickEntityAction;
import ir.layercloud.cortexdrone.action.interact.impl.UseItemAction;
import ir.layercloud.cortexdrone.context.interact.impl.InteractionContext;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived interaction manager. It only parses requests, creates the matching
 * single-use action, serializes the result and manages context lifetime.
 */
public final class InteractionService extends ActionService<InteractionContext> {
    private final InventoryService inventoryService;

    public InteractionService(InventoryService inventoryService) {
        super("interact", "interact_block", "use_item", "pick_entity", "unpick_entity");
        this.inventoryService = inventoryService;
    }

    @Override
    protected InteractionContext createContext(DroneController controller, EntityPlayerMP player,
                                               String operation) {
        return new InteractionContext(controller, player, operation);
    }

    @Override
    protected Action<InteractionContext> createAction(InteractionContext context, String operation,
                                                      JsonObject args) {
        InteractionRequest request = parse(args);
        if ("interact_block".equals(operation)) {
            if (!request.isCoordinates()) {
                return ignored -> context.result(InteractionResult.failure(
                    "Missing x/y/z for interact_block", "activated", false));
            }
            return new InteractBlockAction(this.inventoryService, request);
        }
        if ("use_item".equals(operation)) {
            return request.hasEntity()
                ? new AttackEntityAction(request)
                : new UseItemAction(request);
        }
        if ("pick_entity".equals(operation)) {
            if (!request.hasEntity()) {
                return ignored -> context.result(InteractionResult.failure(
                    "pick_entity requires entityId", "picked", false));
            }
            return new PickEntityAction(request);
        }
        if ("unpick_entity".equals(operation)) return new UnpickEntityAction(request);
        throw new IllegalArgumentException("Unsupported interaction action: " + operation);
    }

    @Override
    protected JsonObject serialize(InteractionContext context) {
        InteractionResult result = context.result();
        if (result == null) return ActionJson.error("Interaction action produced no result");

        JsonObject response = result.isOk()
            ? ActionJson.ok()
            : ActionJson.error(result.getError());
        ActionJson.addAll(response, result.getFields());
        if (result.getStandoff() != null) {
            JsonObject standoff = new JsonObject();
            standoff.addProperty("x", Math.round(result.getStandoff()[0] * 100.0) / 100.0);
            standoff.addProperty("y", Math.round(result.getStandoff()[1] * 100.0) / 100.0);
            standoff.addProperty("z", Math.round(result.getStandoff()[2] * 100.0) / 100.0);
            response.add("standoff", standoff);
        }
        return response;
    }

    private static InteractionRequest parse(JsonObject args) {
        boolean coordinates = ActionArgs.has(args, "x")
            && ActionArgs.has(args, "y")
            && ActionArgs.has(args, "z");
        boolean entityRequested = ActionArgs.has(args, "entityId") || ActionArgs.has(args, "attack");
        int entityId = -1;
        if (entityRequested) {
            try {
                entityId = ActionArgs.integer(args, "entityId",
                    ActionArgs.integer(args, "attack", -1));
            } catch (RuntimeException ignored) {
                entityId = -1;
            }
        }
        return InteractionRequest.builder()
            .coordinates(coordinates)
            .x(coordinates ? args.get("x").getAsInt() : 0)
            .y(coordinates ? args.get("y").getAsInt() : 0)
            .z(coordinates ? args.get("z").getAsInt() : 0)
            .side(ActionArgs.integer(args, "side", 1))
            .item(ActionArgs.string(args, "item", null))
            .slot(ActionArgs.has(args, "slot") ? Integer.valueOf(args.get("slot").getAsInt()) : null)
            .entityId(entityId)
            .entityRequested(entityRequested)
            .build();
    }
}
