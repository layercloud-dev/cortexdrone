package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Player and container read tools: the player's block context, inventory, a
 * single container, and chest searches.
 *
 * <p>Split out of {@code InventoryQueryTools} so each provider has one concern
 * and its own dispatch stays readable.</p>
 */
public final class PlayerContainerQueryTools {
    /**
     * Cardinal directions indexed by vanilla facing.
     */
    private static final String[] FACING_NAMES = {"South(+Z)", "West(-X)", "North(-Z)", "East(+X)"};

    private final InventoryService inventoryService;

    public PlayerContainerQueryTools(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("get_player")
                    .description("Read a player's position, block position, facing direction, yaw/pitch, "
                            + "held item, block underfoot/support block and the block they are looking at. "
                            + "Defaults to the task player; pass name for another online player.")
                    .parameters(ToolSchema.object()
                            .optionalString("name", "Player name; defaults to the task player."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("get_player_inventory")
                    .description("Read a player's 36-slot inventory plus the held slot/item and a compact "
                            + "summary. Use it to check whether the player already has materials before "
                            + "taking them.")
                    .parameters(ToolSchema.object()
                            .optionalString("name", "Player name; defaults to the task player."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("get_chest")
                    .description("Read one chest/container at x/y/z: block name, total/used/free slots and "
                            + "every item stack. Use it before withdrawing or depositing.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Container X.").requiredInt("y", "Container Y.")
                            .requiredInt("z", "Container Z."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("scan_chests")
                    .description("Scan nearby chests/containers for items matching a filter and rank the "
                            + "containers. filter accepts an item name, registry name, ore tag or 'all'.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all")
                            .optionalInt("radius", "Search radius, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".", InventoryLimits.MIN_RADIUS,
                                    InventoryLimits.MAX_RADIUS, InventoryLimits.DEFAULT_CHEST_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("find_item_in_chests")
                    .description("Search ONLY nearby chests/containers for a filtered item. Returns total "
                            + "count, per-container counts and the best container. Faster than find_item "
                            + "when the player only cares about storage.")
                    .parameters(ToolSchema.object()
                            .requiredString("filter", "Item name, registry name or ore dictionary tag (bare or #tag).")
                            .optionalInt("radius", "Search radius, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".", InventoryLimits.MIN_RADIUS,
                                    InventoryLimits.MAX_RADIUS, InventoryLimits.DEFAULT_CHEST_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("find_item")
                    .description("Search the drone inventory, the player inventory and nearby chests for a "
                            + "filtered item. Returns exact counts per source and the best chest. Call this "
                            + "before crafting/withdrawing so you know where the ingredients are.")
                    .parameters(ToolSchema.object()
                            .requiredString("filter", "Item name, registry name or ore dictionary tag (bare or #tag).")
                            .optionalInt("radius", "Chest search radius, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".", InventoryLimits.MIN_RADIUS,
                                    InventoryLimits.MAX_RADIUS, InventoryLimits.DEFAULT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build()
    );

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args) {
        switch (tool) {
            case "get_player":
                return player(ctrl, player, args);
            case "get_player_inventory":
                return playerInventory(ctrl, player, args);
            case "get_chest":
                return chest(ctrl, args);
            case "find_item_in_chests":
            case "scan_chests":
                return chestSearch(ctrl, player, args);
            case "find_item":
                return findEverywhere(ctrl, player, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled player/container tool: " + tool, null, false);
        }
    }

    /**
     * Resolves the requested player, falling back to the task player.
     */
    private static EntityPlayerMP resolve(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String name = ToolArgs.string(args, "name", ctrl.ownerName);
        EntityPlayerMP found = name != null ? CortexDroneMod.findPlayer(name) : player;
        return found != null ? found : player;
    }

    private static JsonObject player(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        EntityPlayerMP target = resolve(ctrl, player, args);
        if (target == null) return InventoryToolSupport.playerOffline();
        World world = ctrl.drone.worldObj;
        int bx = MathHelper.floor_double(target.posX);
        int by = MathHelper.floor_double(target.posY);
        int bz = MathHelper.floor_double(target.posZ);
        int facing = MathHelper.floor_double((double) (target.rotationYaw * 4.0f / 360.0f) + 0.5D) & 3;

        JsonObject result = ToolJson.ok();
        result.addProperty("name", target.getCommandSenderName());
        result.addProperty("entityId", target.getEntityId());
        result.addProperty("x", target.posX);
        result.addProperty("y", target.posY);
        result.addProperty("z", target.posZ);
        result.addProperty("blockX", bx);
        result.addProperty("blockY", by);
        result.addProperty("blockZ", bz);
        // Pickup and carry planning need these three facts, and the live logs
        // show the model guessing them: a stale entity id failed pick_entity,
        // and an underground player was picked up through solid rock.
        boolean canSeeSky = world.canBlockSeeTheSky(bx, by + 1, bz);
        int surfaceY = world.getHeightValue(bx, bz);
        result.addProperty("canSeeSky", canSeeSky);
        result.addProperty("surfaceY", surfaceY);
        result.addProperty("underground", !canSeeSky && surfaceY - by > 2);
        result.addProperty("riding", target.ridingEntity != null);
        result.addProperty("ridingEntityId", target.ridingEntity != null
                ? target.ridingEntity.getEntityId() : -1);
        result.addProperty("ridingEntityName", target.ridingEntity != null
                ? target.ridingEntity.getCommandSenderName() : "");

        Block foot = world.getBlock(bx, by, bz);
        result.addProperty("footBlock", world.isAirBlock(bx, by, bz) ? "Air"
                : ItemClassifier.getBlockDisplayName(foot, world.getBlockMetadata(bx, by, bz)));
        JsonObject support = WorldScanner.findPlayerSupportBlock(world, target);
        Block under = world.getBlock(bx, by - 1, bz);
        String underName = world.isAirBlock(bx, by - 1, bz)
                ? (support != null && support.has("name") ? support.get("name").getAsString() : "Air")
                : ItemClassifier.getBlockDisplayName(under, world.getBlockMetadata(bx, by - 1, bz));
        result.addProperty("underBlock", underName);
        if (support != null) result.add("supportBlock", support);

        JsonObject look = WorldScanner.raycastPlayerLook(world, target, 80.0);
        if (look != null) result.add("lookBlock", look);

        ItemStack held = target.getCurrentEquippedItem();
        boolean hasHeld = held != null && held.stackSize > 0;
        result.addProperty("heldBlock", hasHeld ? held.getDisplayName() : "Empty");
        if (hasHeld) {
            result.add("heldItem", ItemClassifier.itemStackToJson(
                    target.inventory != null ? target.inventory.currentItem : 0, held));
        }
        result.addProperty("yaw", target.rotationYaw);
        result.addProperty("pitch", target.rotationPitch);
        result.addProperty("facing", FACING_NAMES[facing]);
        result.addProperty("facingDx", facing == 1 ? -1 : (facing == 3 ? 1 : 0));
        result.addProperty("facingDz", facing == 0 ? 1 : (facing == 2 ? -1 : 0));
        result.addProperty("health", target.getHealth());
        return result;
    }

    private static JsonObject playerInventory(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        EntityPlayerMP target = resolve(ctrl, player, args);
        if (target == null || target.inventory == null) return InventoryToolSupport.playerOffline();
        JsonObject result = ToolJson.ok();
        result.addProperty("player", target.getCommandSenderName());
        result.addProperty("heldSlot", target.inventory.currentItem);
        ItemStack held = target.getCurrentEquippedItem();
        if (held != null && held.stackSize > 0) {
            result.add("heldItem", ItemClassifier.itemStackToJson(target.inventory.currentItem, held));
        }
        result.add("items", InventoryToolSupport.itemsOf(target.inventory));
        result.addProperty("summary", InventoryService.formatInventorySummary(target.inventory));
        return result;
    }

    private static JsonObject chest(DroneController ctrl, JsonObject args) {
        World world = ctrl.drone.worldObj;
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IInventory)) {
            return ToolJson.error("no_container",
                    "No inventory at (" + x + "," + y + "," + z + ")",
                    "Check the coordinates with get_block, or scan_chests to find containers.", true);
        }
        IInventory inventory = (IInventory) tile;
        Block block = world.getBlock(x, y, z);
        JsonObject result = ToolJson.ok();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("blockName",
                ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(x, y, z)));
        JsonArray items = InventoryToolSupport.itemsOf(inventory);
        int totalSlots = inventory.getSizeInventory();
        result.addProperty("totalSlots", totalSlots);
        result.addProperty("usedSlots", items.size());
        result.addProperty("freeSlots", Math.max(0, totalSlots - items.size()));
        result.add("items", items);
        return result;
    }

    private JsonObject chestSearch(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String query = InventoryToolSupport.firstString(args, "all", "filter", "query", "item");
        return this.inventoryService.getContainerSearchService().searchChestsForItem(
                ctrl.drone.worldObj, ctrl, player, query,
                InventoryToolSupport.radius(args, InventoryLimits.DEFAULT_CHEST_RADIUS));
    }

    private JsonObject findEverywhere(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String query = InventoryToolSupport.firstString(args, "all", "filter", "query", "item");
        return this.inventoryService.getContainerSearchService().findItemEverywhere(
                ctrl.drone.worldObj, ctrl, player, query,
                InventoryToolSupport.radius(args, InventoryLimits.DEFAULT_RADIUS));
    }
}
