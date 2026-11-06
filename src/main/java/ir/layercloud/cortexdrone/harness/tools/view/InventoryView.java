package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.manager.inventory.impl.InventoryManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for inventory actions. Each tool name is already a
 * concrete operation; no {@code op} switch happens here.
 */
@RequiredArgsConstructor
public final class InventoryView {
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
                    .count(ToolArgs.integer(args, "count", 1))
                    .build();
        }

        String filter = first(args, "filter", "target");
        String item = ToolArgs.string(args, "item", null);
        String fromType = ToolArgs.string(args, "from", "drone").toLowerCase();
        String toType = ToolArgs.string(args, "to", "player").toLowerCase();

        int[] source = null;
        if ("withdraw_chest".equals(operation)) {
            source = coords(args, "x", "y", "z");
        } else if ("move_item".equals(operation)) {
            source = coords(args, "fromX", "fromY", "fromZ");
            if (source == null) source = coords(args, "chestX", "chestY", "chestZ");
        }

        int[] target = null;
        if ("deposit_chest".equals(operation)) {
            target = coords(args, "x", "y", "z");
        } else if ("move_item".equals(operation)) {
            target = coords(args, "toX", "toY", "toZ");
            if (target == null) target = coords(args, "chestTargetX", "chestTargetY", "chestTargetZ");
            if (target == null) target = coords(args, "targetX", "targetY", "targetZ");
            if (target == null && !isContainer(fromType)) {
                target = coords(args, "chestX", "chestY", "chestZ");
            }
        }

        return InventoryRequest.builder()
                .operation(operation)
                .filter(filter)
                .item(item)
                .count(ToolArgs.integer(args, "count", 0))
                .playerName(ToolArgs.string(args, "player", null))
                .fromType(fromType)
                .toType(toType)
                .fromSlot(ToolArgs.integer(args, "fromSlot", -1))
                .toSlot(ToolArgs.integer(args, "toSlot", -1))
                .hasSource(source != null)
                .sourceX(source != null ? source[0] : 0)
                .sourceY(source != null ? source[1] : 0)
                .sourceZ(source != null ? source[2] : 0)
                .hasTarget(target != null)
                .targetX(target != null ? target[0] : 0)
                .targetY(target != null ? target[1] : 0)
                .targetZ(target != null ? target[2] : 0)
                .radius(ToolArgs.decimal(args, "radius", 0.0))
                .build();
    }

    private static JsonObject serialize(InventoryResult result) {
        if (result == null) return ToolJson.error("Inventory action produced no result");
        JsonObject response = result.isOk()
                ? ToolJson.ok()
                : ToolJson.error(result.getError());
        ToolJson.addAll(response, result.getFields());
        return response;
    }

    private static String first(JsonObject args, String... keys) {
        for (String key : keys) {
            if (ToolArgs.has(args, key)) return args.get(key).getAsString();
        }
        return null;
    }

    private static int[] coords(JsonObject args, String xKey, String yKey, String zKey) {
        if (!ToolArgs.has(args, xKey) || !ToolArgs.has(args, yKey) || !ToolArgs.has(args, zKey)) {
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
