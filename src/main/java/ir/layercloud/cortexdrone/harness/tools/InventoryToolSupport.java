package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Shared argument and JSON helpers for the inventory query providers.
 *
 * <p>The first argument that exists wins: tools accept {@code filter},
 * {@code query}, {@code target} or {@code item} for the same concept because
 * the model uses them interchangeably. Keeping that fallback in one place stops
 * each provider from spelling the chain differently.</p>
 */
final class InventoryToolSupport {
    private InventoryToolSupport() {
    }

    /** First non-null string among the given keys, or {@code fallback}. */
    static String firstString(JsonObject args, String fallback, String... keys) {
        for (String key : keys) {
            if (ToolArgs.has(args, key)) return args.get(key).getAsString();
        }
        return fallback;
    }

    /** Clamped radius from the shared inventory-query envelope. */
    static int radius(JsonObject args, int fallback) {
        return ToolArgs.clampedInt(args, "radius", fallback,
                InventoryLimits.MIN_RADIUS, InventoryLimits.MAX_RADIUS);
    }

    /** Clamped furnace-scan radius. */
    static int furnaceRadius(JsonObject args, int fallback) {
        return ToolArgs.clampedInt(args, "radius", fallback,
                InventoryLimits.MIN_RADIUS, InventoryLimits.MAX_FURNACE_RADIUS);
    }

    /** Clamped result limit. */
    static int limit(JsonObject args, int fallback) {
        return ToolArgs.clampedInt(args, "limit", fallback, 1, InventoryLimits.MAX_RESULTS);
    }

    /** The machine argument, or an empty string. */
    static String machine(JsonObject args) {
        return firstString(args, "", "machine", "machineKind");
    }

    /** Standard "player is offline" envelope. */
    static JsonObject playerOffline() {
        return ToolJson.error("player_offline", "Player offline",
                "Check the player name with get_player, or wait until they are online.", true);
    }

    /** Item summaries for one inventory. */
    static JsonArray itemsOf(net.minecraft.inventory.IInventory inventory) {
        JsonArray items = new JsonArray();
        if (inventory == null) return items;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) {
                items.add(ir.layercloud.cortexdrone.service.world.ItemClassifier.itemStackToJson(i, stack));
            }
        }
        return items;
    }

    /** Machine recipe box resolution used by both recipe tools. */
    static JsonObject bestRecipe(InventoryService service, net.minecraft.world.World world,
                                 li.cil.oc.common.entity.Drone drone, EntityPlayerMP player,
                                 JsonObject args, String defaultFilter) {
        String filter = firstString(args, defaultFilter, "filter", "target", "item");
        return service.getMachineRecipeService().getBestMachineRecipe(
                world, drone, player, filter, machine(args), radius(args, InventoryLimits.DEFAULT_RADIUS));
    }

    /** Total items in a list of inventories, for summaries. */
    static int totalItems(List<? extends net.minecraft.inventory.IInventory> inventories) {
        int total = 0;
        for (net.minecraft.inventory.IInventory inventory : inventories) {
            total += InventoryService.countInventoryItems(inventory);
        }
        return total;
    }
}
