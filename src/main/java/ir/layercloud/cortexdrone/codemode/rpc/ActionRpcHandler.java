package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.behavior.BuildingService;
import ir.layercloud.cortexdrone.behavior.CombatService;
import ir.layercloud.cortexdrone.behavior.MiningService;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.BuildTask;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Handles physical world actions: mining, box/rect clearing, building,
 * room/box block replacement, structure blueprint construction, farming,
 * liquid plugging, block interaction, auto-torch, hunting, and guard/patrol.
 */
@RequiredArgsConstructor
public class ActionRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
        "queue_mine", "mine_rect", "mine_box", "gather_distant",
        "mine_filter", "break_block", "queue_build", "build_box", "build_structure",
        "replace_room", "replace_box", "replace_blocks", "plug_liquids", "farm",
        "fertilize", "interact_block", "torch", "light", "hunt", "attack_entity", "guard",
        "patrol", "clear_queues", "chop", "use_item"
    };

    private final AIDroneManager manager;
    private final InventoryService inventoryService;
    private final MiningService miningService;
    private final BuildingService buildingService;
    private final CombatService combatService;

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone or world");
            return res;
        }
        if (args == null) {
            args = new JsonObject();
        }
        Drone d = ctrl.drone;
        World world = d.field_70170_p;

        if (m.equals("clear_queues")) {
            int callerLoopId = args.has("loopId") ? args.get("loopId").getAsInt() : 0;
            ctrl.cancelOtherLoops(callerLoopId);
            ctrl.clearAllTaskQueues();
            if (ctrl.mode != Mode.STAY && ctrl.mode != Mode.GUARD && ctrl.mode != Mode.PATROL) {
                this.manager.setDroneMode(ctrl, Mode.FOLLOW, "RPC clear_queues");
            }
            res.addProperty("cleared", true);
            return res;
        }

        if (m.equals("queue_mine")) {
            if (args.has("clear") && args.get("clear").getAsBoolean()) {
                ctrl.mineQueue.clear();
                ctrl.currentMineTarget = null;
            }
            ctrl.mineExactQueueOnly = !args.has("exactOnly") || args.get("exactOnly").getAsBoolean();
            ctrl.returnToOwnerAfterMine = !args.has("deliver") || args.get("deliver").getAsBoolean();
            String explicitFilter = args.has("filter") && !args.get("filter").isJsonNull()
                ? args.get("filter").getAsString().trim() : "";
            String firstBlockId = null;
            int added = 0;
            if (args.has("blocks") && args.get("blocks").isJsonArray()) {
                for (JsonElement el : args.getAsJsonArray("blocks")) {
                    int bx, by, bz;
                    if (el.isJsonArray()) {
                        JsonArray pt = el.getAsJsonArray();
                        bx = pt.get(0).getAsInt(); by = pt.get(1).getAsInt(); bz = pt.get(2).getAsInt();
                    } else if (el.isJsonObject()) {
                        JsonObject pt = el.getAsJsonObject();
                        bx = pt.get("x").getAsInt(); by = pt.get("y").getAsInt(); bz = pt.get("z").getAsInt();
                    } else {
                        continue;
                    }
                    if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) continue;
                    if (firstBlockId == null) {
                        // Neighbour vein propagation must be limited to the block type
                        // the caller actually queued. Leaving a stale/empty mineFilter
                        // made hematite neighbours get queued for an "iron only" task.
                        firstBlockId = ItemClassifier.getBlockIdentifier(
                            world.func_147439_a(bx, by, bz), world.func_72805_g(bx, by, bz));
                    }
                    ctrl.mineQueue.addLast(new int[]{bx, by, bz});
                    added++;
                }
            }
            if (!explicitFilter.isEmpty()) {
                ctrl.mineFilter = explicitFilter;
            } else if (firstBlockId != null) {
                ctrl.mineFilter = firstBlockId;
            }
            if (!ctrl.mineQueue.isEmpty()) {
                this.manager.setDroneMode(ctrl, Mode.MINE, "RPC queue_mine (+" + added + " blocks, total=" + ctrl.mineQueue.size() + ")");
                d.lightColor_$eq(0x00FF66);
                d.statusText_$eq("Mining\n" + ctrl.mineQueue.size() + " blk");
            }
            res.addProperty("queued", added);
            res.addProperty("totalQueued", ctrl.mineQueue.size());
            return res;
        }

        if (m.equals("mine_rect")) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
            int queued = this.miningService.startRectMiningTask(ctrl, player, x1, y1, z1, x2, y2, z2, filter);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("mine_box")) {
            String anchor = args.has("anchor") ? args.get("anchor").getAsString() : "under_player";
            int w = args.has("width") ? args.get("width").getAsInt() : 3;
            int h = args.has("height") ? args.get("height").getAsInt() : 3;
            int l = args.has("length") ? args.get("length").getAsInt() : 3;
            int queued = this.miningService.startBoxMiningTask(ctrl, player, anchor, w, h, l);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("gather_distant") || m.equals("mine_filter")) {
            String target = args.has("target") ? args.get("target").getAsString()
                : (args.has("filter") ? args.get("filter").getAsString() : "ores");
            if (!ItemClassifier.isLogBlock(Block.func_149684_b(target))
                && ir.layercloud.cortexdrone.world.RegistryIntrospector.isLikelyCropOrPlantQuery(target)) {
                int rad = args.has("maxRadius") ? args.get("maxRadius").getAsInt() : (args.has("radius") ? args.get("radius").getAsInt() : 1000);
                int queued = this.miningService.startFarmingTask(ctrl, player, target, rad);
                res.addProperty("queued", queued);
                return res;
            }
            int maxCount = args.has("count") ? args.get("count").getAsInt()
                : (args.has("maxCount") ? args.get("maxCount").getAsInt() : 0);
            boolean untilFull = args.has("untilFull") && args.get("untilFull").getAsBoolean();
            int queued = this.miningService.startMiningTask(ctrl, player, target, maxCount, untilFull);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("break_block")) {
            int bx = args.get("x").getAsInt();
            int by = args.get("y").getAsInt();
            int bz = args.get("z").getAsInt();
            ctrl.mineQueue.clear();
            ctrl.currentMineTarget = null;
            ctrl.mineExactQueueOnly = true;
            ctrl.returnToOwnerAfterMine = false;
            ctrl.minedCount = 0;
            ctrl.mineTargetCount = 1;
            if (ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                ctrl.mineQueue.addLast(new int[]{bx, by, bz});
                this.manager.setDroneMode(ctrl, Mode.MINE, "RPC break_block (" + bx + "," + by + "," + bz + ")");
                d.lightColor_$eq(0x00FF66);
                res.addProperty("queued", 1);
            } else {
                res.addProperty("queued", 0);
            }
            return res;
        }

        if (m.equals("queue_build")) {
            ctrl.allowBuildSubstitution = args.has("substitute") && !args.get("substitute").isJsonNull()
                && args.get("substitute").getAsBoolean();
            if (args.has("clear") && args.get("clear").getAsBoolean()) {
                ctrl.buildQueue.clear();
            }
            String defBlock = args.has("block") ? args.get("block").getAsString() : "any";
            int defMeta = args.has("meta") ? args.get("meta").getAsInt() : -1;
            boolean defReplace = args.has("replace") && args.get("replace").getAsBoolean();
            String defOnlyReplace = args.has("onlyReplace") ? args.get("onlyReplace").getAsString() : null;
            int added = 0;
            String firstBlockFilter = null;
            if (args.has("tasks") && args.get("tasks").isJsonArray()) {
                for (JsonElement el : args.getAsJsonArray("tasks")) {
                    int bx, by, bz;
                    String bf = defBlock;
                    int bm = defMeta;
                    boolean repl = defReplace;
                    String tf = defOnlyReplace;
                    if (el.isJsonObject()) {
                        JsonObject to = el.getAsJsonObject();
                        bx = to.get("x").getAsInt();
                        by = to.get("y").getAsInt();
                        bz = to.get("z").getAsInt();
                        if (to.has("block")) bf = to.get("block").getAsString();
                        if (to.has("meta")) bm = to.get("meta").getAsInt();
                        if (to.has("replace")) repl = to.get("replace").getAsBoolean();
                        if (to.has("onlyReplace")) tf = to.get("onlyReplace").getAsString();
                    } else if (el.isJsonArray()) {
                        JsonArray pt = el.getAsJsonArray();
                        bx = pt.get(0).getAsInt();
                        by = pt.get(1).getAsInt();
                        bz = pt.get(2).getAsInt();
                    } else {
                        continue;
                    }
                    if (firstBlockFilter == null && bf != null) {
                        firstBlockFilter = bf;
                    }
                    if (repl) {
                        if (ItemClassifier.isProtectedFixture(world, bx, by, bz)) continue;
                        Block curB = world.func_147439_a(bx, by, bz);
                        int curM = world.func_72805_g(bx, by, bz);
                        if (!world.func_147437_c(bx, by, bz) && !ItemClassifier.isLiquidBlock(curB)) {
                            String curDisp = ItemClassifier.getBlockDisplayName(curB, curM);
                            String curId = ItemClassifier.getBlockIdentifier(curB, curM);
                            if (ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, bf) && (bm < 0 || curM == bm)) {
                                continue;
                            }
                            if (tf != null && !tf.isEmpty() && !tf.equalsIgnoreCase("all") && !tf.equalsIgnoreCase("any")
                                && !ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, tf)) {
                                continue;
                            }
                        }
                    }
                    ctrl.buildQueue.addLast(new BuildTask(bx, by, bz, bf, bm, repl, tf));
                    added++;
                }
            }
            if (firstBlockFilter != null && InventoryService.findPlaceableItemSlot(d.mainInventory(), firstBlockFilter) < 0) {
                int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, firstBlockFilter, 64);
                if (pulled == 0 && player != null) {
                    this.inventoryService.takeFromPlayerInventory(ctrl, player, firstBlockFilter, 64);
                }
            }
            if (!ctrl.buildQueue.isEmpty()) {
                this.manager.setDroneMode(ctrl, Mode.BUILD, "RPC queue_build (+" + added + " tasks, total=" + ctrl.buildQueue.size() + ")");
                d.lightColor_$eq(0x33AAFF);
                d.statusText_$eq("Building\n" + ctrl.buildQueue.size() + " blk");
            }
            res.addProperty("queued", added);
            res.addProperty("totalQueued", ctrl.buildQueue.size());
            return res;
        }

        if (m.equals("build_box")) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            String bf = args.has("block") ? args.get("block").getAsString() : "any";
            int bm = args.has("meta") ? args.get("meta").getAsInt() : -1;
            boolean hollow = args.has("hollow") && args.get("hollow").getAsBoolean();
            int queued = this.buildingService.startBuildBoxTask(ctrl, x1, y1, z1, x2, y2, z2, bf, bm, hollow);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("build_structure")) {
            return this.buildingService.startBuildStructureTask(ctrl, player, args);
        }

        if (m.equals("replace_room")) {
            String surf = args.has("surface") ? args.get("surface").getAsString() : "floor";
            String bf = args.has("block") ? args.get("block").getAsString()
                : (args.has("with") ? args.get("with").getAsString() : "any");
            String tf = args.has("onlyReplace") ? args.get("onlyReplace").getAsString()
                : (args.has("targetFilter") ? args.get("targetFilter").getAsString() : null);
            int bm = args.has("meta") ? args.get("meta").getAsInt() : -1;
            int queued = this.buildingService.startReplaceRoomTask(ctrl, player, surf, bf, tf, bm);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("replace_box")) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            String bf = args.has("block") ? args.get("block").getAsString()
                : (args.has("with") ? args.get("with").getAsString() : "any");
            String tf = args.has("onlyReplace") ? args.get("onlyReplace").getAsString()
                : (args.has("targetFilter") ? args.get("targetFilter").getAsString() : null);
            int bm = args.has("meta") ? args.get("meta").getAsInt() : -1;
            int queued = this.buildingService.startReplaceBoxTask(ctrl, player, x1, y1, z1, x2, y2, z2, bf, tf, bm);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("replace_blocks")) {
            String bf = args.has("block") ? args.get("block").getAsString() : "any";
            String tf = args.has("onlyReplace") ? args.get("onlyReplace").getAsString() : null;
            int bm = args.has("meta") ? args.get("meta").getAsInt() : -1;
            List<int[]> coords = new ArrayList<int[]>();
            if (args.has("blocks") && args.get("blocks").isJsonArray()) {
                for (JsonElement el : args.getAsJsonArray("blocks")) {
                    if (el.isJsonArray()) {
                        JsonArray pt = el.getAsJsonArray();
                        coords.add(new int[]{pt.get(0).getAsInt(), pt.get(1).getAsInt(), pt.get(2).getAsInt()});
                    } else if (el.isJsonObject()) {
                        JsonObject pt = el.getAsJsonObject();
                        coords.add(new int[]{pt.get("x").getAsInt(), pt.get("y").getAsInt(), pt.get("z").getAsInt()});
                    }
                }
            }
            int queued = this.buildingService.startReplaceBlocksTask(ctrl, player, coords, bf, tf, bm);
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("plug_liquids")) {
            String bf = args.has("block") ? args.get("block").getAsString() : "any";
            int queued;
            if (args.has("x1") && args.has("x2")) {
                queued = this.buildingService.plugLiquidsInBox(ctrl,
                    args.get("x1").getAsInt(), args.get("y1").getAsInt(), args.get("z1").getAsInt(),
                    args.get("x2").getAsInt(), args.get("y2").getAsInt(), args.get("z2").getAsInt(), bf);
            } else {
                int r = args.has("radius") ? Math.min(16, args.get("radius").getAsInt()) : 12;
                int cx = MathHelper.func_76128_c(d.field_70165_t);
                int cy = MathHelper.func_76128_c(d.field_70163_u);
                int cz = MathHelper.func_76128_c(d.field_70161_v);
                queued = this.buildingService.plugLiquidsInBox(ctrl, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, bf);
            }
            res.addProperty("queued", queued);
            return res;
        }

        if (m.equals("farm")) {
            String target = args.has("target") ? args.get("target").getAsString()
                : (args.has("filter") ? args.get("filter").getAsString() : "crop");
            int radius = args.has("radius") ? Math.min(1200, Math.max(16, args.get("radius").getAsInt())) : 48;
            int queued = this.miningService.startFarmingTask(ctrl, player, target, radius);
            res.addProperty("queued", queued);
            res.addProperty("harvested", queued);
            return res;
        }

        if (m.equals("chop")) {
            // Chop = break the log block at (x,y,z) and chain-chop connected log columns
            if (!args.has("x") || !args.has("y") || !args.has("z") || args.get("x").isJsonNull() || args.get("y").isJsonNull() || args.get("z").isJsonNull()) {
                res.addProperty("ok", false);
                res.addProperty("error", "Missing x/y/z for chop");
                res.addProperty("queued", 0);
                return res;
            }
            int x = args.get("x").getAsInt(), y = args.get("y").getAsInt(), z = args.get("z").getAsInt();
            Block b = world.func_147439_a(x, y, z);
            int queued = 0;
            if (ItemClassifier.isLogBlock(b)) {
                ctrl.mineQueue.clear();
                ctrl.mineQueue.addFirst(new int[]{x, y, z});
                // chain up the column
                for (int dy = 1; dy <= 64; dy++) {
                    Block above = world.func_147439_a(x, y + dy, z);
                    if (!ItemClassifier.isLogBlock(above)) break;
                    ctrl.mineQueue.addLast(new int[]{x, y + dy, z});
                    queued++;
                }
                queued++;
                this.manager.setDroneMode(ctrl, Mode.MINE, "Chopping log at (" + x + "," + y + "," + z + ")");
                res.addProperty("queued", queued);
                return res;
            }
            res.addProperty("ok", false);
            res.addProperty("error", "Target block is not a log");
            return res;
        }

        if (m.equals("fertilize")) {
            int radius = args.has("radius") && !args.get("radius").isJsonNull() ? Math.min(24, Math.max(4, args.get("radius").getAsInt())) : 12;
            if (this.miningService == null || this.miningService.getFarmingService() == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "Farming service unavailable");
                res.addProperty("fertilized", 0);
                return res;
            }
            int fertilized = this.miningService.getFarmingService().fertilizeNearbyCrops(world, ctrl, player, radius);
            res.addProperty("fertilized", fertilized);
            return res;
        }

        if (m.equals("interact_block")) {
            if (!args.has("x") || !args.has("y") || !args.has("z") || args.get("x").isJsonNull() || args.get("y").isJsonNull() || args.get("z").isJsonNull()) {
                res.addProperty("ok", false);
                res.addProperty("error", "Missing x/y/z for interact_block");
                res.addProperty("activated", false);
                return res;
            }
            int x = args.get("x").getAsInt(), y = args.get("y").getAsInt(), z = args.get("z").getAsInt();
            Block b = world.func_147439_a(x, y, z);
            if (ItemClassifier.isLiquidBlock(b) && world.func_72805_g(x, y, z) == 0) {
                boolean scooped = this.inventoryService.scoopLiquidAt(world, ctrl, player, x, y, z);
                res.addProperty("activated", scooped);
                res.addProperty("scooped", scooped);
                return res;
            }
            boolean activated = false;
            boolean itemUsed = false;
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("activated", false);
                return res;
            }
            net.minecraft.item.ItemStack held = null;
            syncFakePlayerToDrone(fakePlayer, d);
            if (fakePlayer.field_71071_by != null) {
                int heldSlot = fakePlayer.field_71071_by.field_70461_c;
                if (heldSlot >= 0 && heldSlot < fakePlayer.field_71071_by.func_70302_i_()) {
                    held = fakePlayer.field_71071_by.func_70301_a(heldSlot);
                }
            }
            int side = args.has("side") && !args.get("side").isJsonNull() ? args.get("side").getAsInt() : 1;
            // Vanilla order: block activation first, then the held item's onItemUse.
            if (b != null) {
                activated = b.func_149727_a(world, x, y, z, fakePlayer, side, 0.5f, 0.5f, 0.5f);
            }
            if (!activated && held != null && held.func_77973_b() != null) {
                try {
                    itemUsed = held.func_77973_b().func_77648_a(held, fakePlayer, world, x, y, z, side, 0.5f, 0.5f, 0.5f);
                } catch (Throwable ignored) {
                }
            }
            res.addProperty("activated", activated);
            res.addProperty("itemUsed", itemUsed);
            if (held != null) res.addProperty("heldItem", held.func_82833_r());
            res.addProperty("block", ItemClassifier.getBlockDisplayName(world.func_147439_a(x, y, z), world.func_72805_g(x, y, z)));
            return res;
        }

        if (m.equals("use_item")) {
            // Simulate a player using an item. This used to go through
            // Class.getMethod("onItemUse"/"onItemRightClick"), which never matches at
            // runtime: Forge 1.7.10 classes are SRG-named (func_77648_a/func_77659_a),
            // so every call silently failed and useItem always reported used=false.
            // Call the real hooks directly and support an explicit target block.
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null || fakePlayer.field_71071_by == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("used", false);
                return res;
            }
            int slot = args.has("slot") && !args.get("slot").isJsonNull()
                ? args.get("slot").getAsInt() : fakePlayer.field_71071_by.field_70461_c;
            if (args.has("item") && !args.get("item").isJsonNull()) {
                String filter = args.get("item").getAsString();
                int found = -1;
                // Prefer the hotbar (0..8), then fall back to the whole drone inventory.
                int size = fakePlayer.field_71071_by.func_70302_i_();
                for (int pass = 0; pass < 2 && found < 0; pass++) {
                    int from = pass == 0 ? 0 : 9;
                    int to = pass == 0 ? Math.min(9, size) : size;
                    for (int i = from; i < to; i++) {
                        net.minecraft.item.ItemStack st = fakePlayer.field_71071_by.func_70301_a(i);
                        if (st == null || st.func_77973_b() == null) continue;
                        if (filter.isEmpty() || ItemClassifier.scoreBlockOrItemMatch(
                                st.func_82833_r(), ItemClassifier.getItemIdentifier(st),
                                st.func_77960_j(), filter) > 0) {
                            found = i;
                            break;
                        }
                    }
                }
                if (found < 0) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "No matching item in drone inventory for filter: " + filter);
                    res.addProperty("used", false);
                    return res;
                }
                slot = found;
            }
            net.minecraft.item.ItemStack stack = slot >= 0 && slot < fakePlayer.field_71071_by.func_70302_i_()
                ? fakePlayer.field_71071_by.func_70301_a(slot) : null;
            if (stack == null || stack.func_77973_b() == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No item in slot " + slot);
                res.addProperty("used", false);
                return res;
            }
            // Make sure the fake player actually holds the stack it is about to use.
            try {
                if (slot >= 0 && slot < 9) {
                    fakePlayer.field_71071_by.field_70461_c = slot;
                }
            } catch (Throwable ignored) {}
            syncFakePlayerToDrone(fakePlayer, d);

            int tx, ty, tz, side;
            boolean hasTarget = args.has("x") && args.has("y") && args.has("z")
                && !args.get("x").isJsonNull() && !args.get("y").isJsonNull() && !args.get("z").isJsonNull();
            if (hasTarget) {
                tx = args.get("x").getAsInt();
                ty = args.get("y").getAsInt();
                tz = args.get("z").getAsInt();
            } else {
                // No explicit target: click the top face of the block under the drone,
                // which is exactly where a hovering player would right-click.
                tx = MathHelper.func_76128_c(d.field_70165_t);
                ty = MathHelper.func_76128_c(d.field_70163_u) - 1;
                tz = MathHelper.func_76128_c(d.field_70161_v);
            }
            side = args.has("side") && !args.get("side").isJsonNull() ? args.get("side").getAsInt() : 1;

            net.minecraft.item.Item item = stack.func_77973_b();
            int beforeCount = stack.field_77994_a;
            int beforeDamage = stack.func_77960_j();
            boolean used = false;
            String method = "none";
            try {
                used = item.func_77648_a(stack, fakePlayer, world, tx, ty, tz, side, 0.5f, 0.5f, 0.5f);
                method = "onItemUse";
            } catch (Throwable t) {
                method = "onItemUse:error:" + t.getClass().getSimpleName();
            }
            if (!used && !hasTarget) {
                try {
                    net.minecraft.item.ItemStack after = item.func_77659_a(stack, world, fakePlayer);
                    used = after != null && (after != stack
                        || after.field_77994_a != beforeCount
                        || after.func_77960_j() != beforeDamage);
                    method = "onItemRightClick";
                } catch (Throwable t) {
                    method = "onItemRightClick:error:" + t.getClass().getSimpleName();
                }
            }
            res.addProperty("used", used);
            res.addProperty("method", method);
            res.addProperty("slot", slot);
            res.addProperty("item", stack.func_82833_r());
            res.addProperty("x", tx);
            res.addProperty("y", ty);
            res.addProperty("z", tz);
            res.addProperty("side", side);
            Block targetBlock = world.func_147439_a(tx, ty, tz);
            res.addProperty("block", ItemClassifier.getBlockDisplayName(targetBlock, world.func_72805_g(tx, ty, tz)));
            return res;
        }

        if (m.equals("torch") || m.equals("light")) {
            // Torch-free illumination aura (kept under the legacy "torch" RPC name).
            return this.manager.getLightingService().configure(ctrl, args);
        }

        if (m.equals("hunt")) {
            String filter = args.has("filter") ? args.get("filter").getAsString()
                : (args.has("target") ? args.get("target").getAsString() : "sheep|cow|pig");
            int count = args.has("count") ? args.get("count").getAsInt() : 10;
            int nearby = this.combatService.startHuntTask(ctrl, player, filter, count);
            res.addProperty("nearbyMatching", nearby);
            res.addProperty("targetCount", count);
            return res;
        }

        if (m.equals("attack_entity")) {
            int entityId = args.has("id") && !args.get("id").isJsonNull() ? args.get("id").getAsInt()
                : (args.has("entityId") && !args.get("entityId").isJsonNull() ? args.get("entityId").getAsInt() : -1);
            String filter = args.has("filter") && !args.get("filter").isJsonNull() ? args.get("filter").getAsString()
                : (args.has("target") && !args.get("target").isJsonNull() ? args.get("target").getAsString() : "hostile");
            if (this.combatService == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "Combat service unavailable");
                res.addProperty("started", false);
                return res;
            }
            boolean started = this.combatService.startAttackEntityTask(ctrl, player, entityId, filter);
            res.addProperty("started", started);
            res.addProperty("targetId", ctrl.targetAttackEntityId);
            return res;
        }

        if (m.equals("guard")) {
            ctrl.targetPlayerName = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString()
                : (player != null ? player.func_70005_c_() : ctrl.ownerName);
            ctrl.guardFilter = args.has("filter") && !args.get("filter").isJsonNull()
                ? args.get("filter").getAsString()
                : "hostile|mutant";
            this.manager.setDroneMode(ctrl, Mode.GUARD, "RPC " + m + " " + ctrl.targetPlayerName);
            return res;
        }

        if (m.equals("patrol")) {
            ctrl.targetPlayerName = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString()
                : (player != null ? player.func_70005_c_() : ctrl.ownerName);
            ctrl.patrolRadius = args.has("radius") ? Math.max(12, Math.min(80, args.get("radius").getAsInt())) : 36;
            ctrl.guardFilter = args.has("filter") && !args.get("filter").isJsonNull()
                ? args.get("filter").getAsString()
                : "hostile|mutant";
            this.manager.setDroneMode(ctrl, Mode.PATROL, "RPC patrol (radius=" + ctrl.patrolRadius + "m, filter=" + ctrl.guardFilter + ")");
            return res;
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled action RPC method: " + m);
        return res;
    }

    /**
     * OC's agent FakePlayer keeps its own position; sync it to the drone so
     * canPlayerEdit / reach / spawn-protection checks see the real location.
     */
    private static void syncFakePlayerToDrone(net.minecraft.entity.player.EntityPlayer fp, Drone d) {
        if (fp == null || d == null) return;
        try {
            fp.field_70165_t = d.field_70165_t;
            fp.field_70163_u = d.field_70163_u;
            fp.field_70161_v = d.field_70161_v;
            fp.field_71093_bK = d.field_71093_bK;
            fp.field_70177_z = d.field_70177_z;
            fp.field_70125_A = d.field_70125_A;
        } catch (Throwable ignored) {
        }
    }
}
