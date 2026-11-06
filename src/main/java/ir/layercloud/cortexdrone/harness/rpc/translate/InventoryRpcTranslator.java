package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.inventory.impl.InventoryManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for inventory actions.
 */
@RequiredArgsConstructor
public final class InventoryRpcTranslator {
    private final InventoryManager service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        InventoryResult result = this.service.handle(
                controller, player, operation, parse(operation, args));
        return serialize(result);
    }

    private static InventoryRequest parse(String operation, JsonObject args) {
        if ("craft".equals(operation)) {
            String filter = first(args, "filter", "item", "target");
            return InventoryRequest.builder()
                    .operation("craft")
                    .filter(filter != null ? filter : "planks")
                    .count(RpcArgs.integer(args, "count", 1))
                    .build();
        }

        String op = RpcArgs.string(args, "op", "move").trim().toLowerCase();
        if ("moveslot".equals(op)) op = "move";
        else if ("withdrawchest".equals(op)) op = "withdraw";
        else if ("depositchest".equals(op)) op = "deposit";
        else if ("givetoplayer".equals(op)) op = "give";
        else if ("takefromplayer".equals(op)) op = "take";
        else if ("suckitems".equals(op)) op = "suck";

        String filter = first(args, "filter", "target");
        String item = RpcArgs.string(args, "item", null);
        String fromType = RpcArgs.string(args, "from", "drone").toLowerCase();
        String toType = RpcArgs.string(args, "to", "player").toLowerCase();

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
                .count(RpcArgs.integer(args, "count", 0))
                .playerName(RpcArgs.string(args, "player", null))
                .fromType(fromType)
                .toType(toType)
                .fromSlot(RpcArgs.integer(args, "fromSlot", -1))
                .toSlot(RpcArgs.integer(args, "toSlot", -1))
                .hasSource(source != null)
                .sourceX(source != null ? source[0] : 0)
                .sourceY(source != null ? source[1] : 0)
                .sourceZ(source != null ? source[2] : 0)
                .hasTarget(target != null)
                .targetX(target != null ? target[0] : 0)
                .targetY(target != null ? target[1] : 0)
                .targetZ(target != null ? target[2] : 0)
                .radius(RpcArgs.decimal(args, "radius", 0.0))
                .build();
    }

    private static JsonObject serialize(InventoryResult result) {
        if (result == null) return RpcJson.error("Inventory action produced no result");
        JsonObject response = result.isOk()
                ? RpcJson.ok()
                : RpcJson.error(result.getError());
        RpcJson.addAll(response, result.getFields());
        return response;
    }

    private static String first(JsonObject args, String... keys) {
        for (String key : keys) {
            if (RpcArgs.has(args, key)) return args.get(key).getAsString();
        }
        return null;
    }

    private static int[] coords(JsonObject args, String xKey, String yKey, String zKey) {
        if (!RpcArgs.has(args, xKey) || !RpcArgs.has(args, yKey) || !RpcArgs.has(args, zKey)) {
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
