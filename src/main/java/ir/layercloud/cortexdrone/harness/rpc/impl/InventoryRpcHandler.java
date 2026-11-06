package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
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

import java.util.List;

/**
 * Read-only inventory RPCs: player, chest, furnace, crafting and machine
 * inspection. Mutating transfer/craft operations are actions in
 * action/inventory and are handled by InventoryManager.
 */
@RequiredArgsConstructor
public class InventoryRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
            "get_player", "get_player_inventory", "scan_chests",
            "find_item_in_chests", "find_item", "get_chest", "get_recipes",
            "scan_furnaces", "get_machine_recipes", "get_machine_recipe",
            "scan_machines", "machine_ingredients"
    };

    private final InventoryService inventoryService;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.worldObj;

        if (m.equals("get_player")) {
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

        if (m.equals("get_player_inventory")) {
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

        if (m.equals("find_item")) {
            String query = args.has("filter") ? args.get("filter").getAsString()
                    : (args.has("query") ? args.get("query").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : "all"));
            int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
            return this.inventoryService.getContainerSearchService().findItemEverywhere(world, ctrl, player, query, radius);
        }

        if (m.equals("find_item_in_chests") || m.equals("scan_chests")) {
            String query = args.has("filter") ? args.get("filter").getAsString()
                    : (args.has("query") ? args.get("query").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : "all"));
            int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 28;
            return this.inventoryService.getContainerSearchService().searchChestsForItem(world, ctrl, player, query, radius);
        }

        if (m.equals("get_chest")) {
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

        if (m.equals("get_recipes")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : "chest");
            return this.inventoryService.getRecipesForFilter(filter);
        }

        if (m.equals("scan_furnaces")) {
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

        if (m.equals("get_machine_recipes")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                    : (args.has("target") ? args.get("target").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : ""));
            String machine = args.has("machine") ? args.get("machine").getAsString()
                    : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
            return this.inventoryService.getMachineRecipeService().getMachineRecipesForFilter(filter, machine);
        }

        if (m.equals("get_machine_recipe")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                    : (args.has("target") ? args.get("target").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : "plate_iron"));
            String machine = args.has("machine") ? args.get("machine").getAsString()
                    : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
            int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
            return this.inventoryService.getMachineRecipeService().getBestMachineRecipe(world, player, filter, machine, radius);
        }

        if (m.equals("scan_machines")) {
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

        if (m.equals("machine_ingredients")) {
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

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled inventory RPC method: " + m);
        return res;
    }
}
