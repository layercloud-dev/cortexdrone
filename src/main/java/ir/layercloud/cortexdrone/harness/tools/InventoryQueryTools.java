package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.machine.FurnaceScanner;
import ir.layercloud.cortexdrone.service.machine.MachineTileInspector;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Read-only inventory tools: player, chest, furnace, crafting and machine
 * inspection. Mutating inventory tools are actions in
 * action/inventory and are handled by InventoryManager.
 */
@RequiredArgsConstructor
public class InventoryQueryTools implements ToolProvider {
    private final InventoryService inventoryService;

    public static List<ToolSpec> specs() {
        return Arrays.asList(
                ToolSpec.builder("get_player")
                        .description("Read a player's position, block position, facing direction, yaw/pitch, "
                                + "held item, block underfoot/support block and the block they are looking at. "
                                + "Defaults to the task player; pass name for another online player.")
                        .parameters(ToolSchema.object()
                                .optionalString("name", "Player name; defaults to the task player."))
                        .affinity(ToolAffinity.MAIN).timeoutMs(3_000L).build(),
                ToolSpec.builder("get_player_inventory")
                        .description("Read a player's 36-slot inventory plus the held slot/item and a compact "
                                + "summary. Use it to check whether the player already has materials before "
                                + "taking them.")
                        .parameters(ToolSchema.object()
                                .optionalString("name", "Player name; defaults to the task player."))
                        .affinity(ToolAffinity.MAIN).timeoutMs(3_000L).build(),
                ToolSpec.builder("get_chest")
                        .description("Read one chest/container at x/y/z: block name, total/used/free slots and "
                                + "every item stack. Use it before withdrawing or depositing.")
                        .parameters(ToolSchema.object()
                                .requiredInt("x", "Container X.").requiredInt("y", "Container Y.")
                                .requiredInt("z", "Container Z."))
                        .affinity(ToolAffinity.MAIN).timeoutMs(3_000L).build(),
                ToolSpec.builder("scan_chests")
                        .description("Scan nearby chests/containers for items matching a filter and rank the "
                                + "containers. filter accepts an item name, registry name, ore tag or 'all'.")
                        .parameters(ToolSchema.object()
                                .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all")
                                .optionalInt("radius", "Search radius, 4..64.", 4, 64, 28))
                        .affinity(ToolAffinity.MAIN).timeoutMs(5_000L).build(),
                ToolSpec.builder("find_item_in_chests")
                        .description("Search ONLY nearby chests/containers for a filtered item. Returns total "
                                + "count, per-container counts and the best container. Faster than find_item "
                                + "when the player only cares about storage.")
                        .parameters(ToolSchema.object()
                                .requiredString("filter", "Item name, registry name or ore dictionary tag.")
                                .optionalInt("radius", "Search radius, 4..64.", 4, 64, 28))
                        .affinity(ToolAffinity.MAIN).timeoutMs(5_000L).build(),
                ToolSpec.builder("find_item")
                        .description("Search the drone inventory, the player inventory and nearby chests for a "
                                + "filtered item. Returns exact counts per source and the best chest. Call this "
                                + "before crafting/withdrawing so you know where the ingredients are.")
                        .parameters(ToolSchema.object()
                                .requiredString("filter", "Item name, registry name or ore dictionary tag.")
                                .optionalInt("radius", "Chest search radius, 4..64.", 4, 64, 32))
                        .affinity(ToolAffinity.MAIN).timeoutMs(5_000L).build(),
                ToolSpec.builder("get_recipes")
                        .description("List vanilla crafting-table recipes whose result matches a filter, "
                                + "including shaped/shapeless grid and ingredient counts. Does NOT include "
                                + "machine-only recipes; use get_machine_recipes for those.")
                        .parameters(ToolSchema.object()
                                .optionalString("filter", "Result item name/registry/ore tag.", "chest"))
                        .affinity(ToolAffinity.MAIN).timeoutMs(5_000L).build(),
                ToolSpec.builder("scan_furnaces")
                        .description("Scan nearby furnaces and report position, burn/cook progress and every "
                                + "slot's contents. Useful for smelting tasks and fuel checks.")
                        .parameters(ToolSchema.object()
                                .optionalInt("radius", "Search radius, 4..36.", 4, 36, 24))
                        .affinity(ToolAffinity.MAIN).timeoutMs(4_000L).build(),
                ToolSpec.builder("get_machine_recipes")
                        .description("List configured machine recipes for a filter, optionally restricted to a "
                                + "machine kind (assembly_machine, chemical_plant, blast_furnace, centrifuge, "
                                + "mixer, refinery...). Sources include HBM JSON configs and live hooks.")
                        .parameters(ToolSchema.object()
                                .optionalString("filter", "Input/output/item name or phrase.", "")
                                .optionalString("machine", "Machine kind/alias to restrict results."))
                        .affinity(ToolAffinity.MAIN).timeoutMs(6_000L).build(),
                ToolSpec.builder("get_machine_recipe")
                        .description("Find the best machine recipe for a target item and machine kind, ranked "
                                + "by ingredient availability around the player. Returns the recipe, machine and "
                                + "input list, or an explicit machine_config_only blocker.")
                        .parameters(ToolSchema.object()
                                .requiredString("filter", "Target item/result name.")
                                .optionalString("machine", "Machine kind/alias.")
                                .optionalInt("radius", "Chest search radius for ingredients, 4..64.", 4, 64, 32))
                        .affinity(ToolAffinity.MAIN).timeoutMs(8_000L).build(),
                ToolSpec.builder("scan_machines")
                        .description("Scan nearby machine tiles and describe each one: kind, power/fluid status, "
                                + "inputs/outputs and progress. filter/machine narrows by machine kind.")
                        .parameters(ToolSchema.object()
                                .optionalString("machine", "Machine kind/alias, or a name substring.", "")
                                .optionalInt("radius", "Search radius, 4..64.", 4, 64, 32))
                        .affinity(ToolAffinity.MAIN).timeoutMs(5_000L).build(),
                ToolSpec.builder("machine_ingredients")
                        .description("For the best machine recipe matching a target, check each ingredient's "
                                + "availability in the drone, player and nearby chests. Use it to decide whether "
                                + "to withdraw, craft, mine, or report a blocker.")
                        .parameters(ToolSchema.object()
                                .requiredString("filter", "Target item/result name.")
                                .optionalString("machine", "Machine kind/alias.")
                                .optionalInt("radius", "Chest search radius, 4..64.", 4, 64, 32))
                        .affinity(ToolAffinity.MAIN).timeoutMs(8_000L).build()
        );
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : specs()) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> handle(context.controller(), context.player(), name, arguments));
        }
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        args = ToolArgs.camelAliases(args);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.worldObj;

        if ("get_chest".equals(m)
                && (args == null || !args.has("x") || !args.has("y") || !args.has("z"))) {
            return ToolJson.error("get_chest requires x/y/z");
        }

        switch (m) {
            case "get_player": {
                String pName = args.has("name") && !args.get("name").isJsonNull() ? args.get("name").getAsString() : ctrl.ownerName;
                EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : player;
                if (p == null) p = player;
                if (p == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "Player offline");
                    return res;
                }
                int bx = MathHelper.floor_double(p.posX);
                int by = MathHelper.floor_double(p.posY);
                int bz = MathHelper.floor_double(p.posZ);
                int facing = MathHelper.floor_double((double) (p.rotationYaw * 4.0f / 360.0f) + 0.5D) & 3;
                int dirX = facing == 1 ? -1 : (facing == 3 ? 1 : 0);
                int dirZ = facing == 0 ? 1 : (facing == 2 ? -1 : 0);
                String[] dirs = new String[]{"South(+Z)", "West(-X)", "North(-Z)", "East(+X)"};
                Block foot = world.getBlock(bx, by, bz);
                Block under = world.getBlock(bx, by - 1, bz);
                JsonObject supportBlock = WorldScanner.findPlayerSupportBlock(world, p);
                JsonObject lookBlock = WorldScanner.raycastPlayerLook(world, p, 80.0);
                ItemStack held = p.getCurrentEquippedItem();
                res.addProperty("name", p.getCommandSenderName());
                res.addProperty("x", p.posX);
                res.addProperty("y", p.posY);
                res.addProperty("z", p.posZ);
                res.addProperty("blockX", bx);
                res.addProperty("blockY", by);
                res.addProperty("blockZ", bz);
                res.addProperty("footBlock", world.isAirBlock(bx, by, bz) ? "Air" : ItemClassifier.getBlockDisplayName(foot, world.getBlockMetadata(bx, by, bz)));
                String underName = world.isAirBlock(bx, by - 1, bz)
                        ? (supportBlock != null && supportBlock.has("name") ? supportBlock.get("name").getAsString() : "Air")
                        : ItemClassifier.getBlockDisplayName(under, world.getBlockMetadata(bx, by - 1, bz));
                res.addProperty("underBlock", underName);
                if (supportBlock != null) res.add("supportBlock", supportBlock);
                if (lookBlock != null) res.add("lookBlock", lookBlock);
                res.addProperty("heldBlock", (held != null && held.stackSize > 0) ? held.getDisplayName() : "Empty");
                if (held != null && held.stackSize > 0) {
                    res.add("heldItem", ItemClassifier.itemStackToJson(p.inventory != null ? p.inventory.currentItem : 0, held));
                }
                res.addProperty("yaw", p.rotationYaw);
                res.addProperty("pitch", p.rotationPitch);
                res.addProperty("facing", dirs[facing]);
                res.addProperty("facingDx", dirX);
                res.addProperty("facingDz", dirZ);
                res.addProperty("health", p.getHealth());
                return res;
            }
            case "get_player_inventory": {
                String pName = args.has("name") && !args.get("name").isJsonNull() ? args.get("name").getAsString() : ctrl.ownerName;
                EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : player;
                if (p == null) p = player;
                if (p == null || p.inventory == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "Player offline");
                    return res;
                }
                res.addProperty("player", p.getCommandSenderName());
                res.addProperty("heldSlot", p.inventory.currentItem);
                ItemStack held = p.getCurrentEquippedItem();
                if (held != null && held.stackSize > 0) {
                    res.add("heldItem", ItemClassifier.itemStackToJson(p.inventory.currentItem, held));
                }
                JsonArray items = new JsonArray();
                for (int i = 0; i < 36; i++) {
                    ItemStack st = p.inventory.getStackInSlot(i);
                    if (st != null && st.stackSize > 0) {
                        items.add(ItemClassifier.itemStackToJson(i, st));
                    }
                }
                res.add("items", items);
                res.addProperty("summary", InventoryService.formatInventorySummary(p.inventory));
                return res;
            }
            case "find_item": {
                String query = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("query") ? args.get("query").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : "all"));
                int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
                return this.inventoryService.getContainerSearchService().findItemEverywhere(world, ctrl, player, query, radius);
            }
            case "find_item_in_chests":
            case "scan_chests": {
                String query = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("query") ? args.get("query").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : "all"));
                int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 28;
                return this.inventoryService.getContainerSearchService().searchChestsForItem(world, ctrl, player, query, radius);
            }
            case "get_chest": {
                int x = args.get("x").getAsInt(), y = args.get("y").getAsInt(), z = args.get("z").getAsInt();
                TileEntity te = world.getTileEntity(x, y, z);
                if (!(te instanceof IInventory)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "No inventory at (" + x + "," + y + "," + z + ")");
                    return res;
                }
                IInventory inv = (IInventory) te;
                Block b = world.getBlock(x, y, z);
                int meta = world.getBlockMetadata(x, y, z);
                res.addProperty("x", x);
                res.addProperty("y", y);
                res.addProperty("z", z);
                res.addProperty("blockName", ItemClassifier.getBlockDisplayName(b, meta));
                JsonArray items = new JsonArray();
                int totalSlots = inv.getSizeInventory();
                for (int i = 0; i < totalSlots; i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (st != null && st.stackSize > 0) {
                        items.add(ItemClassifier.itemStackToJson(i, st));
                    }
                }
                res.addProperty("totalSlots", totalSlots);
                res.addProperty("usedSlots", items.size());
                res.addProperty("freeSlots", Math.max(0, totalSlots - items.size()));
                res.add("items", items);
                return res;
            }
            case "get_recipes": {
                String filter = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : "chest");
                return this.inventoryService.getRecipesForFilter(filter);
            }
            case "scan_furnaces": {
                int radius = args.has("radius") ? Math.min(36, Math.max(4, args.get("radius").getAsInt())) : 24;
                List<TileEntity> furnaces = FurnaceScanner.findAllFurnaceTiles(world, d.posX, d.posY, d.posZ, radius);
                JsonArray arr = new JsonArray();
                for (TileEntity te : furnaces) {
                    if (te instanceof IInventory) {
                        IInventory inv = (IInventory) te;
                        JsonObject fo = new JsonObject();
                        fo.addProperty("x", te.xCoord);
                        fo.addProperty("y", te.yCoord);
                        fo.addProperty("z", te.zCoord);
                        fo.addProperty("dist", Math.round(d.getDistance(te.xCoord + 0.5, te.yCoord + 0.5, te.zCoord + 0.5) * 10.0) / 10.0);
                        if (te instanceof TileEntityFurnace) {
                            TileEntityFurnace tf = (TileEntityFurnace) te;
                            fo.addProperty("burnTime", tf.furnaceBurnTime);
                            fo.addProperty("cookTime", tf.furnaceCookTime);
                        }
                        JsonArray items = new JsonArray();
                        for (int i = 0; i < inv.getSizeInventory(); i++) {
                            ItemStack st = inv.getStackInSlot(i);
                            if (st != null && st.stackSize > 0) {
                                items.add(ItemClassifier.itemStackToJson(i, st));
                            }
                        }
                        fo.add("items", items);
                        arr.add(fo);
                    }
                }
                res.addProperty("count", arr.size());
                res.add("furnaces", arr);
                return res;
            }
            case "get_machine_recipes": {
                String filter = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("target") ? args.get("target").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : ""));
                String machine = args.has("machine") ? args.get("machine").getAsString()
                        : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
                return this.inventoryService.getMachineRecipeService().getMachineRecipesForFilter(filter, machine);
            }
            case "get_machine_recipe": {
                String filter = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("target") ? args.get("target").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : "plate_iron"));
                String machine = args.has("machine") ? args.get("machine").getAsString()
                        : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
                int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
                return this.inventoryService.getMachineRecipeService().getBestMachineRecipe(world, player, filter, machine, radius);
            }
            case "scan_machines": {
                String machine = args.has("machine") ? args.get("machine").getAsString()
                        : (args.has("machineKind") ? args.get("machineKind").getAsString()
                        : (args.has("filter") ? args.get("filter").getAsString() : ""));
                int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
                String want = MachineTileInspector.normalizeMachineAlias(machine);
                double refX = player != null && player.worldObj == world ? player.posX : d.posX;
                double refY = player != null && player.worldObj == world ? player.posY : d.posY;
                double refZ = player != null && player.worldObj == world ? player.posZ : d.posZ;
                List<TileEntity> machines = MachineTileInspector.findMachineTiles(world, refX, refY, refZ, radius, want);
                JsonArray arr = new JsonArray();
                for (TileEntity te : machines) arr.add(MachineTileInspector.describeMachineTile(world, te));
                res.addProperty("count", arr.size());
                res.add("machines", arr);
                return res;
            }
            case "machine_ingredients": {
                String filter = args.has("filter") ? args.get("filter").getAsString()
                        : (args.has("target") ? args.get("target").getAsString()
                        : (args.has("item") ? args.get("item").getAsString() : "plate_iron"));
                String machine = args.has("machine") ? args.get("machine").getAsString()
                        : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
                int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
                JsonObject best = this.inventoryService.getMachineRecipeService().getBestMachineRecipe(world, player, filter, machine, radius);
                if (!best.has("recipe")) return best;
                JsonObject recipe = best.getAsJsonObject("recipe");
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("machine", recipe.has("machine") ? recipe.get("machine").getAsString() : "");
                out.addProperty("recipe", recipe.has("recipe") ? recipe.get("recipe").getAsString() : "");
                if (recipe.has("inputs")) {
                    JsonArray ins = recipe.getAsJsonArray("inputs");
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < ins.size(); i++) {
                        JsonObject io = ins.get(i).getAsJsonObject();
                        String probe = io.has("displayName") ? io.get("displayName").getAsString()
                                : (io.has("registryName") ? io.get("registryName").getAsString()
                                : (io.has("dict") ? io.get("dict").getAsString() : "ingredient"));
                        JsonObject search = this.inventoryService.getContainerSearchService().findItemEverywhere(world, ctrl, player, probe, radius);
                        JsonObject entry = new JsonObject();
                        entry.add("ingredient", io);
                        entry.addProperty("probe", probe);
                        entry.addProperty("found", search.has("found") && search.get("found").getAsBoolean());
                        entry.addProperty("totalAvailable", search.has("totalAvailable") ? search.get("totalAvailable").getAsInt() : 0);
                        entry.addProperty("inDroneCount", search.has("inDroneCount") ? search.get("inDroneCount").getAsInt() : 0);
                        entry.addProperty("inPlayerCount", search.has("inPlayerCount") ? search.get("inPlayerCount").getAsInt() : 0);
                        entry.addProperty("inChestsCount", search.has("inChestsCount") ? search.get("inChestsCount").getAsInt() : 0);
                        if (search.has("bestChest")) entry.add("bestChest", search.get("bestChest"));
                        arr.add(entry);
                    }
                    out.add("ingredients", arr);
                }
                return out;
            }
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled inventory tool: " + m);
        return res;
    }
}
