package ir.layercloud.cortexdrone.service.inventory.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.action.inventory.impl.CraftAction;
import ir.layercloud.cortexdrone.action.inventory.impl.DepositChestAction;
import ir.layercloud.cortexdrone.action.inventory.impl.DropAction;
import ir.layercloud.cortexdrone.action.inventory.impl.GiveToPlayerAction;
import ir.layercloud.cortexdrone.action.inventory.impl.MoveSlotAction;
import ir.layercloud.cortexdrone.action.inventory.impl.SuckItemsAction;
import ir.layercloud.cortexdrone.action.inventory.impl.TakeFromPlayerAction;
import ir.layercloud.cortexdrone.action.inventory.impl.WithdrawChestAction;
import ir.layercloud.cortexdrone.context.inventory.impl.InventoryContext;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Long-lived inventory action manager. It parses transfer/craft requests,
 * creates one single-use action, serializes the result and manages the context.
 * Read-only inventory inspection stays in InventoryRpcHandler.
 */
public final class InventoryActionService extends ActionService<InventoryContext> {
    private final AIDroneManager manager;
    private final InventoryService inventoryService;

    public InventoryActionService(AIDroneManager manager, InventoryService inventoryService) {
        super("inventory", "transfer", "craft");
        this.manager = manager;
        this.inventoryService = inventoryService;
    }

    @Override
    protected InventoryContext createContext(DroneController controller, EntityPlayerMP player,
                                             String operation) {
        return new InventoryContext(controller, player, operation);
    }

    @Override
    protected Action<InventoryContext> createAction(InventoryContext context, String operation,
                                                    JsonObject args) {
        InventoryRequest request = parse(operation, args);
        if ("craft".equals(operation)) {
            return new CraftAction(this.inventoryService, request);
        }
        String op = request.getOperation();
        if ("take".equals(op)) return new TakeFromPlayerAction(this.inventoryService, request);
        if ("give".equals(op)) return new GiveToPlayerAction(this.manager, this.inventoryService, request);
        if ("move".equals(op)) return new MoveSlotAction(this.inventoryService, request);
        if ("withdraw".equals(op)) return new WithdrawChestAction(this.inventoryService, request);
        if ("deposit".equals(op)) return new DepositChestAction(this.manager, this.inventoryService, request);
        if ("drop".equals(op)) return new DropAction(this.manager, request);
        if ("suck".equals(op)) return new SuckItemsAction(request);
        return ignored -> context.result(InventoryResult.failure(
            "Unknown transfer op: " + op));
    }

    @Override
    protected JsonObject serialize(InventoryContext context) {
        InventoryResult result = context.result();
        if (result == null) return ActionJson.error("Inventory action produced no result");
        JsonObject response = result.isOk()
            ? ActionJson.ok()
            : ActionJson.error(result.getError());
        ActionJson.addAll(response, result.getFields());
        return response;
    }

    private static InventoryRequest parse(String operation, JsonObject args) {
        if ("craft".equals(operation)) {
            String filter = first(args, "filter", "item", "target");
            return InventoryRequest.builder()
                .operation("craft")
                .filter(filter != null ? filter : "planks")
                .count(ActionArgs.integer(args, "count", 1))
                .build();
        }

        String op = ActionArgs.string(args, "op", "move").trim().toLowerCase();
        if ("moveslot".equals(op)) op = "move";
        else if ("withdrawchest".equals(op)) op = "withdraw";
        else if ("depositchest".equals(op)) op = "deposit";
        else if ("givetoplayer".equals(op)) op = "give";
        else if ("takefromplayer".equals(op)) op = "take";
        else if ("suckitems".equals(op)) op = "suck";
        String filter = first(args, "filter", "target");
        String item = ActionArgs.string(args, "item", null);
        String fromType = ActionArgs.string(args, "from", "drone").toLowerCase();
        String toType = ActionArgs.string(args, "to", "player").toLowerCase();

        int[] source = null;
        if ("withdraw".equals(op)) {
            source = coords(args, "x", "y", "z");
        } else if ("move".equals(op)) {
            source = coords(args, "fromX", "fromY", "fromZ");
            if (source == null) source = coords(args, "chestX", "chestY", "chestZ");
        }

        int[] target = null;
        if ("deposit".equals(op)) {
            target = coords(args, "x", "y", "z");
        } else if ("move".equals(op)) {
            target = coords(args, "toX", "toY", "toZ");
            if (target == null) target = coords(args, "chestTargetX", "chestTargetY", "chestTargetZ");
            if (target == null) target = coords(args, "targetX", "targetY", "targetZ");
            if (target == null && !isContainer(fromType)) {
                target = coords(args, "chestX", "chestY", "chestZ");
            }
        }

        return InventoryRequest.builder()
            .operation(op)
            .filter(filter)
            .item(item)
            .count(ActionArgs.integer(args, "count", 0))
            .playerName(ActionArgs.string(args, "player", null))
            .fromType(fromType)
            .toType(toType)
            .fromSlot(ActionArgs.integer(args, "fromSlot", -1))
            .toSlot(ActionArgs.integer(args, "toSlot", -1))
            .hasSource(source != null)
            .sourceX(source != null ? source[0] : 0)
            .sourceY(source != null ? source[1] : 0)
            .sourceZ(source != null ? source[2] : 0)
            .hasTarget(target != null)
            .targetX(target != null ? target[0] : 0)
            .targetY(target != null ? target[1] : 0)
            .targetZ(target != null ? target[2] : 0)
            .radius(ActionArgs.decimal(args, "radius", 0.0))
            .build();
    }

    private static String first(JsonObject args, String... keys) {
        for (String key : keys) {
            if (ActionArgs.has(args, key)) return args.get(key).getAsString();
        }
        return null;
    }

    private static int[] coords(JsonObject args, String xKey, String yKey, String zKey) {
        if (!ActionArgs.has(args, xKey) || !ActionArgs.has(args, yKey) || !ActionArgs.has(args, zKey)) {
            return null;
        }
        return new int[]{
            args.get(xKey).getAsInt(),
            args.get(yKey).getAsInt(),
            args.get(zKey).getAsInt()
        };
    }

    private static boolean isContainer(String type) {
        return "chest".equals(type) || "crate".equals(type)
            || "container".equals(type) || "furnace".equals(type);
    }
}
