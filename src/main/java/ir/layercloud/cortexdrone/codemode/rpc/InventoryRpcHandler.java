package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.inventory.ChestSorter;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.SmeltingService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Handles player, chest, furnace, crafting, smelting, and fluid inventory RPCs.
 */
@RequiredArgsConstructor
public class InventoryRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
        "get_player", "get_player_inventory", "take_from_player", "give_to_player",
        "move_slot", "scan_chests", "find_item_in_chests", "find_item",
        "get_chest", "withdraw_chest", "deposit_chest", "sort_chests",
        "transfer_containers", "drop", "suck_items", "craft", "get_recipes",
        "scan_furnaces", "smelt", "cook", "scoop_liquid", "place_liquid",
        "machine_craft", "get_machine_recipes", "get_machine_recipe",
        "scan_machines", "machine_ingredients", "anvil_drive"
    };

    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final ChestSorter chestSorter;
    private final SmeltingService smeltingService;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.field_70170_p;

        if (m.equals("get_player")) {
            String pName = args.has("name") && !args.get("name").isJsonNull() ? args.get("name").getAsString() : ctrl.ownerName;
            EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : player;
            if (p == null) p = player;
            if (p == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "Player offline");
                return res;
            }
            int bx = MathHelper.func_76128_c(p.field_70165_t);
            int by = MathHelper.func_76128_c(p.field_70163_u);
            int bz = MathHelper.func_76128_c(p.field_70161_v);
            int facing = MathHelper.func_76128_c((double) (p.field_70177_z * 4.0f / 360.0f) + 0.5D) & 3;
            int dirX = facing == 1 ? -1 : (facing == 3 ? 1 : 0);
            int dirZ = facing == 0 ? 1 : (facing == 2 ? -1 : 0);
            String[] dirs = new String[]{"South(+Z)", "West(-X)", "North(-Z)", "East(+X)"};
            Block foot = world.func_147439_a(bx, by, bz);
            Block under = world.func_147439_a(bx, by - 1, bz);
            JsonObject supportBlock = WorldScanner.findPlayerSupportBlock(world, p);
            JsonObject lookBlock = WorldScanner.raycastPlayerLook(world, p, 80.0);
            ItemStack held = p.func_71045_bC();
            res.addProperty("name", p.func_70005_c_());
            res.addProperty("x", p.field_70165_t);
            res.addProperty("y", p.field_70163_u);
            res.addProperty("z", p.field_70161_v);
            res.addProperty("blockX", bx);
            res.addProperty("blockY", by);
            res.addProperty("blockZ", bz);
            res.addProperty("footBlock", world.func_147437_c(bx, by, bz) ? "Air" : ItemClassifier.getBlockDisplayName(foot, world.func_72805_g(bx, by, bz)));
            String underName = world.func_147437_c(bx, by - 1, bz)
                ? (supportBlock != null && supportBlock.has("name") ? supportBlock.get("name").getAsString() : "Air")
                : ItemClassifier.getBlockDisplayName(under, world.func_72805_g(bx, by - 1, bz));
            res.addProperty("underBlock", underName);
            if (supportBlock != null) res.add("supportBlock", supportBlock);
            if (lookBlock != null) res.add("lookBlock", lookBlock);
            res.addProperty("heldBlock", (held != null && held.field_77994_a > 0) ? held.func_82833_r() : "Empty");
            if (held != null && held.field_77994_a > 0) {
                res.add("heldItem", ItemClassifier.itemStackToJson(p.field_71071_by != null ? p.field_71071_by.field_70461_c : 0, held));
            }
            res.addProperty("yaw", p.field_70177_z);
            res.addProperty("pitch", p.field_70125_A);
            res.addProperty("facing", dirs[facing]);
            res.addProperty("facingDx", dirX);
            res.addProperty("facingDz", dirZ);
            res.addProperty("health", p.func_110143_aJ());
            return res;
        }

        if (m.equals("get_player_inventory")) {
            String pName = args.has("name") && !args.get("name").isJsonNull() ? args.get("name").getAsString() : ctrl.ownerName;
            EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : player;
            if (p == null) p = player;
            if (p == null || p.field_71071_by == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "Player offline");
                return res;
            }
            res.addProperty("player", p.func_70005_c_());
            res.addProperty("heldSlot", p.field_71071_by.field_70461_c);
            ItemStack held = p.func_71045_bC();
            if (held != null && held.field_77994_a > 0) {
                res.add("heldItem", ItemClassifier.itemStackToJson(p.field_71071_by.field_70461_c, held));
            }
            JsonArray items = new JsonArray();
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.field_71071_by.func_70301_a(i);
                if (st != null && st.field_77994_a > 0) {
                    items.add(ItemClassifier.itemStackToJson(i, st));
                }
            }
            res.add("items", items);
            res.addProperty("summary", InventoryService.formatInventorySummary(p.field_71071_by));
            return res;
        }

        if (m.equals("take_from_player")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString() : "any");
            int count = args.has("count") ? args.get("count").getAsInt() : 512;
            EntityPlayerMP p = args.has("player") ? CortexDroneMod.findPlayer(args.get("player").getAsString()) : player;
            if (p == null) p = CortexDroneMod.findPlayer(ctrl.ownerName);
            int moved = this.inventoryService.takeFromPlayerInventory(ctrl, p, filter, count);
            res.addProperty("transferred", moved);
            return res;
        }

        if (m.equals("give_to_player")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString() : "all");
            int count = args.has("count") ? args.get("count").getAsInt() : 512;
            EntityPlayerMP p = args.has("player") ? CortexDroneMod.findPlayer(args.get("player").getAsString()) : player;
            if (p == null) p = CortexDroneMod.findPlayer(ctrl.ownerName);
            if (p != null && p.field_70170_p == world && d.func_70032_d(p) > 4.2 && InventoryService.countInventoryItems(d.mainInventory()) > 0) {
                ctrl.dropFilter = filter;
                this.manager.setDroneMode(ctrl, Mode.DROP, "RPC deliver_to_player (" + filter + ")");
                res.addProperty("flyingToPlayer", true);
                return res;
            }
            int given = this.inventoryService.giveToPlayerInventory(ctrl, p, filter, count);
            res.addProperty("transferred", given);
            return res;
        }

        if (m.equals("move_slot")) {
            String fromType = args.has("from") ? args.get("from").getAsString().toLowerCase() : "drone";
            String toType = args.has("to") ? args.get("to").getAsString().toLowerCase() : "player";
            int fromSlot = args.has("fromSlot") ? args.get("fromSlot").getAsInt() : -1;
            int toSlot = args.has("toSlot") ? args.get("toSlot").getAsInt() : -1;
            int count = args.has("count") ? args.get("count").getAsInt() : 64;
            String itemFilter = args.has("item") ? args.get("item").getAsString()
                : (args.has("filter") ? args.get("filter").getAsString() : null);
            EntityPlayerMP p = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);

            TileEntity srcTe = null;
            if (args.has("fromX") && args.has("fromY") && args.has("fromZ")) {
                srcTe = world.func_147438_o(args.get("fromX").getAsInt(), args.get("fromY").getAsInt(), args.get("fromZ").getAsInt());
            } else if (args.has("chestX") && args.has("chestY") && args.has("chestZ")) {
                srcTe = world.func_147438_o(args.get("chestX").getAsInt(), args.get("chestY").getAsInt(), args.get("chestZ").getAsInt());
            } else {
                srcTe = this.inventoryService.findNearestInventoryTile(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 24);
            }

            TileEntity dstTe = null;
            if (args.has("toX") && args.has("toY") && args.has("toZ")) {
                dstTe = world.func_147438_o(args.get("toX").getAsInt(), args.get("toY").getAsInt(), args.get("toZ").getAsInt());
            } else if (args.has("chestTargetX") && args.has("chestTargetY") && args.has("chestTargetZ")) {
                dstTe = world.func_147438_o(args.get("chestTargetX").getAsInt(), args.get("chestTargetY").getAsInt(), args.get("chestTargetZ").getAsInt());
            } else if (args.has("targetX") && args.has("targetY") && args.has("targetZ")) {
                dstTe = world.func_147438_o(args.get("targetX").getAsInt(), args.get("targetY").getAsInt(), args.get("targetZ").getAsInt());
            } else if (args.has("chestX") && args.has("chestY") && args.has("chestZ") && !fromType.equals("chest") && !fromType.equals("crate") && !fromType.equals("container")) {
                dstTe = world.func_147438_o(args.get("chestX").getAsInt(), args.get("chestY").getAsInt(), args.get("chestZ").getAsInt());
            } else {
                dstTe = this.inventoryService.findNearestInventoryWithSpace(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 24, srcTe instanceof IInventory ? (IInventory) srcTe : d.mainInventory());
            }

            boolean fromWorldContainer = fromType.equals("chest") || fromType.equals("crate") || fromType.equals("container") || fromType.equals("furnace");
            boolean toWorldContainer = toType.equals("chest") || toType.equals("crate") || toType.equals("container") || toType.equals("furnace");
            IInventory srcInv = fromType.equals("player") && p != null ? p.field_71071_by
                : (fromWorldContainer && srcTe instanceof IInventory ? (IInventory) srcTe : d.mainInventory());
            IInventory dstInv = toType.equals("player") && p != null ? p.field_71071_by
                : (toWorldContainer && dstTe instanceof IInventory ? (IInventory) dstTe : d.mainInventory());
            int moved = this.inventoryService.moveInventorySlot(srcInv, fromSlot, itemFilter, dstInv, toSlot, count, p);
            res.addProperty("moved", moved);
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
            TileEntity te = world.func_147438_o(x, y, z);
            if (!(te instanceof IInventory)) {
                res.addProperty("ok", false);
                res.addProperty("error", "No inventory at (" + x + "," + y + "," + z + ")");
                return res;
            }
            IInventory inv = (IInventory) te;
            Block b = world.func_147439_a(x, y, z);
            int meta = world.func_72805_g(x, y, z);
            res.addProperty("x", x);
            res.addProperty("y", y);
            res.addProperty("z", z);
            res.addProperty("blockName", ItemClassifier.getBlockDisplayName(b, meta));
            JsonArray items = new JsonArray();
            int totalSlots = inv.func_70302_i_();
            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0) {
                    items.add(ItemClassifier.itemStackToJson(i, st));
                }
            }
            res.addProperty("totalSlots", totalSlots);
            res.addProperty("usedSlots", items.size());
            res.addProperty("freeSlots", Math.max(0, totalSlots - items.size()));
            res.add("items", items);
            return res;
        }

        if (m.equals("withdraw_chest")) {
            String filter = args.has("filter") ? args.get("filter").getAsString() : "any";
            int count = args.has("count") ? args.get("count").getAsInt() : 64;
            int moved = 0;
            if (args.has("x") && args.has("y") && args.has("z")) {
                TileEntity te = world.func_147438_o(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
                if (!(te instanceof IInventory)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "No chest found at specified coordinates");
                    return res;
                }
                moved = InventoryService.withdrawFromInventory((IInventory) te, d.mainInventory(), ItemClassifier.resolveHeldFilter(player, filter), count);
                if (moved < count && te instanceof TileEntityChest) {
                    TileEntityChest tc = (TileEntityChest) te;
                    tc.func_145979_i();
                    TileEntityChest[] adj = new TileEntityChest[]{tc.field_145992_i, tc.field_145990_j, tc.field_145991_k, tc.field_145988_l};
                    for (TileEntityChest a : adj) {
                        if (a != null && moved < count) {
                            moved += InventoryService.withdrawFromInventory(a, d.mainInventory(), ItemClassifier.resolveHeldFilter(player, filter), count - moved);
                        }
                    }
                }
            } else {
                moved = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, filter, count);
            }
            res.addProperty("withdrawn", moved);
            return res;
        }

        if (m.equals("deposit_chest")) {
            if (args.has("x") && args.has("y") && args.has("z")) {
                int cx = args.get("x").getAsInt(), cy = args.get("y").getAsInt(), cz = args.get("z").getAsInt();
                TileEntity te = world.func_147438_o(cx, cy, cz);
                if (te instanceof IInventory) {
                    if (d.func_70011_f(cx + 0.5, cy + 0.5, cz + 0.5) <= 4.2) {
                        int moved = this.inventoryService.depositDroneItemsIntoTile(d, te);
                        res.addProperty("deposited", moved);
                        return res;
                    }
                    ctrl.targetDepositChestPos = new int[]{cx, cy, cz};
                    this.manager.setDroneMode(ctrl, Mode.DEPOSIT, "RPC deposit_chest at (" + cx + "," + cy + "," + cz + ")");
                    res.addProperty("flyingToChest", true);
                    return res;
                }
            }
            ctrl.targetDepositChestPos = null;
            if (InventoryService.countInventoryItems(d.mainInventory()) > 0) {
                this.manager.setDroneMode(ctrl, Mode.DEPOSIT, "RPC deposit_chest");
                res.addProperty("flyingToChest", true);
            } else {
                res.addProperty("deposited", 0);
            }
            return res;
        }

        if (m.equals("sort_chests")) {
            int radius = args.has("radius") ? args.get("radius").getAsInt() : 24;
            String mode = args.has("mode") ? args.get("mode").getAsString() : "all";
            return this.chestSorter.sortNearbyChests(world, ctrl, player, radius, mode);
        }

        if (m.equals("transfer_containers")) {
            String fromF = args.has("from") ? args.get("from").getAsString() : "crate";
            String toF = args.has("to") ? args.get("to").getAsString() : "chest";
            int radius = args.has("radius") ? args.get("radius").getAsInt() : 32;
            boolean create = !args.has("autoCreateChests") || args.get("autoCreateChests").getAsBoolean();
            boolean sort = !args.has("sortAfter") || args.get("sortAfter").getAsBoolean();
            return this.chestSorter.transferContainers(world, ctrl, player, fromF, toF, radius, create, sort);
        }

        if (m.equals("drop")) {
            ctrl.dropFilter = args.has("filter") ? args.get("filter").getAsString() : "all";
            if (InventoryService.countInventoryItems(d.mainInventory()) > 0) {
                this.manager.setDroneMode(ctrl, Mode.DROP, "RPC drop");
            }
            return res;
        }

        if (m.equals("suck_items")) {
            double radius = args.has("radius") ? Math.min(16.0, Math.max(1.0, args.get("radius").getAsDouble())) : 6.5;
            InventoryService.suckNearbyItems(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, radius);
            res.addProperty("totalItems", InventoryService.countInventoryItems(d.mainInventory()));
            return res;
        }

        if (m.equals("craft")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("item") ? args.get("item").getAsString()
                : (args.has("target") ? args.get("target").getAsString() : "planks"));
            int count = args.has("count") ? args.get("count").getAsInt() : 1;
            return this.inventoryService.craftItem(world, ctrl, player, filter, count);
        }

        if (m.equals("get_recipes")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : "chest");
            return this.inventoryService.getRecipesForFilter(filter);
        }

        if (m.equals("scan_furnaces")) {
            int radius = args.has("radius") ? Math.min(36, Math.max(4, args.get("radius").getAsInt())) : 24;
            List<TileEntity> furnaces = this.smeltingService.findAllFurnaceTiles(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, radius);
            JsonArray arr = new JsonArray();
            for (TileEntity te : furnaces) {
                if (te instanceof IInventory) {
                    IInventory inv = (IInventory) te;
                    JsonObject fo = new JsonObject();
                    fo.addProperty("x", te.field_145851_c);
                    fo.addProperty("y", te.field_145848_d);
                    fo.addProperty("z", te.field_145849_e);
                    fo.addProperty("dist", Math.round(d.func_70011_f(te.field_145851_c + 0.5, te.field_145848_d + 0.5, te.field_145849_e + 0.5) * 10.0) / 10.0);
                    if (te instanceof TileEntityFurnace) {
                        TileEntityFurnace tf = (TileEntityFurnace) te;
                        fo.addProperty("burnTime", tf.field_145956_a);
                        fo.addProperty("cookTime", tf.field_145961_j);
                    }
                    JsonArray items = new JsonArray();
                    for (int i = 0; i < inv.func_70302_i_(); i++) {
                        ItemStack st = inv.func_70301_a(i);
                        if (st != null && st.field_77994_a > 0) {
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

        if (m.equals("smelt") || m.equals("cook")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : "meat"));
            int count = args.has("count") ? args.get("count").getAsInt() : 64;
            int queued = this.smeltingService.startSmeltTask(ctrl, player, filter, count);
            res.addProperty("queued", queued);
            res.addProperty("mode", ctrl.mode.name());
            return res;
        }

        if (m.equals("scoop_liquid")) {
            if (args.has("x") && args.has("y") && args.has("z")) {
                int lx = args.get("x").getAsInt(), ly = args.get("y").getAsInt(), lz = args.get("z").getAsInt();
                if (d.func_70011_f(lx + 0.5, ly + 0.5, lz + 0.5) <= 4.5) {
                    boolean scooped = this.inventoryService.scoopLiquidAt(world, ctrl, player, lx, ly, lz);
                    res.addProperty("scooped", scooped ? 1 : 0);
                    return res;
                }
            }
            String type = args.has("type") ? args.get("type").getAsString()
                : (args.has("target") ? args.get("target").getAsString()
                : (args.has("filter") ? args.get("filter").getAsString() : "lava"));
            int count = args.has("count") ? args.get("count").getAsInt() : 1;
            int sources = this.inventoryService.startScoopLiquidTask(ctrl, player, type, count);
            res.addProperty("queued", sources);
            res.addProperty("sourcesFound", sources);
            res.addProperty("targetCount", count);
            return res;
        }

        if (m.equals("place_liquid")) {
            int lx = args.get("x").getAsInt(), ly = args.get("y").getAsInt(), lz = args.get("z").getAsInt();
            String type = args.has("type") ? args.get("type").getAsString()
                : (args.has("liquid") ? args.get("liquid").getAsString() : "water");
            boolean placed = this.inventoryService.placeLiquidFromBucket(world, ctrl, player, lx, ly, lz, type);
            res.addProperty("placed", placed);
            return res;
        }

        if (m.equals("machine_craft")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : "plate_iron"));
            String machine = args.has("machine") ? args.get("machine").getAsString()
                : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
            int count = args.has("count") ? args.get("count").getAsInt() : 1;
            int radius = args.has("radius") ? Math.min(64, Math.max(8, args.get("radius").getAsInt())) : 32;
            return this.inventoryService.getMachineCraftExecutor().machineCraft(world, ctrl, player, filter, machine, count, radius);
        }

        if (m.equals("get_machine_recipes")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : ""));
            String machine = args.has("machine") ? args.get("machine").getAsString()
                : (args.has("machineKind") ? args.get("machineKind").getAsString() : "");
            return this.inventoryService.getMachineCraftExecutor().machineCraftRecipes(filter, machine);
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
            return this.inventoryService.getMachineCraftExecutor().scanMachines(world, ctrl, player, machine, radius);
        }

        if (m.equals("anvil_drive")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : "plate_iron"));
            int radius = args.has("radius") ? Math.min(64, Math.max(4, args.get("radius").getAsInt())) : 32;
            return this.inventoryService.getAnvilService().driveAnvil(world, ctrl, player, filter, radius);
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
