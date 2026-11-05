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
        "fertilize", "interact_block", "torch", "hunt", "attack_entity", "guard",
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
            int added = 0;
            if (args.has("blocks") && args.get("blocks").isJsonArray()) {
                for (JsonElement el : args.getAsJsonArray("blocks")) {
                    if (el.isJsonArray()) {
                        JsonArray pt = el.getAsJsonArray();
                        int bx = pt.get(0).getAsInt(), by = pt.get(1).getAsInt(), bz = pt.get(2).getAsInt();
                        if (ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                            ctrl.mineQueue.addLast(new int[]{bx, by, bz});
                            added++;
                        }
                    } else if (el.isJsonObject()) {
                        JsonObject pt = el.getAsJsonObject();
                        int bx = pt.get("x").getAsInt(), by = pt.get("y").getAsInt(), bz = pt.get("z").getAsInt();
                        if (ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                            ctrl.mineQueue.addLast(new int[]{bx, by, bz});
                            added++;
                        }
                    }
                }
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
            if (b != null) {
                activated = b.func_149727_a(world, x, y, z, d.player(), 0, 0.5f, 0.5f, 0.5f);
            }
            res.addProperty("activated", activated);
            return res;
        }

        if (m.equals("use_item")) {
            // use_item = simulate player use of an item: doors, levers, eating, buckets (if present)
            int slot = args.has("slot") && !args.get("slot").isJsonNull() ? args.get("slot").getAsInt() : 0;
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null || fakePlayer.field_71071_by == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("used", false);
                return res;
            }
            if (args.has("item") && !args.get("item").isJsonNull()) {
                String filter = args.get("item").getAsString();
                java.util.List<net.minecraft.item.ItemStack> candidates = new java.util.ArrayList<>();
                java.util.List<Integer> candidateSlots = new java.util.ArrayList<>();
                // hotbar slot 0..8
                for (int i = 0; i < 9 && i < fakePlayer.field_71071_by.func_70302_i_(); i++) {
                    net.minecraft.item.ItemStack st = fakePlayer.field_71071_by.func_70301_a(i);
                    if (st == null || st.func_77973_b() == null) continue;
                    if (filter == null || filter.isEmpty() || ir.layercloud.cortexdrone.world.ItemClassifier.scoreBlockOrItemMatch(st.func_82833_r(), ir.layercloud.cortexdrone.world.ItemClassifier.getItemIdentifier(st), st.func_77960_j(), filter) > 0) {
                        candidates.add(st);
                        candidateSlots.add(i);
                    }
                }
                if (candidates.isEmpty()) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "No matching item in drone hotbar for filter: " + filter);
                    res.addProperty("used", false);
                    return res;
                }
                slot = candidateSlots.get(0);
            }
            net.minecraft.item.ItemStack stack = slot >= 0 && slot < fakePlayer.field_71071_by.func_70302_i_()
                ? fakePlayer.field_71071_by.func_70301_a(slot) : null;
            if (stack == null || stack.func_77973_b() == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No item in slot " + slot);
                res.addProperty("used", false);
                return res;
            }
            // Simulate holding the selected slot so Item.onItemUse sees the right held stack.
            try {
                fakePlayer.field_71071_by.field_70461_c = slot;
            } catch (Throwable ignored) {}

            // optimize: call the two common hooks by reflection (Forge signature-compatible).
            boolean used = false;
            try {
                java.lang.reflect.Method useMethod = stack.func_77973_b().getClass().getMethod("onItemUse", net.minecraft.item.ItemStack.class, net.minecraft.world.World.class, net.minecraft.entity.player.EntityPlayer.class);
                Object result = useMethod.invoke(stack.func_77973_b(), stack, world, fakePlayer);
                used = result instanceof Boolean ? ((Boolean) result).booleanValue() : result != null;
            } catch (Throwable ignored) {}
            // Fallback: many items handle use via onItemUseFirst / onItemRightClick
            if (!used) {
                try {
                    java.lang.reflect.Method rc = stack.func_77973_b().getClass().getMethod("onItemRightClick", net.minecraft.item.ItemStack.class, net.minecraft.world.World.class, net.minecraft.entity.player.EntityPlayer.class);
                    Object result = rc.invoke(stack.func_77973_b(), stack, world, fakePlayer);
                    used = result instanceof Boolean ? ((Boolean) result).booleanValue() : result != null;
                } catch (Throwable ignored) {}
            }
            res.addProperty("used", used);
            res.addProperty("slot", slot);
            res.addProperty("item", stack.func_82833_r());
            return res;
        }

        if (m.equals("torch")) {
            if (args.has("enabled")) {
                ctrl.autoTorch = args.get("enabled").getAsBoolean();
            } else {
                ctrl.autoTorch = !ctrl.autoTorch;
            }
            boolean placed = this.buildingService.placeTorchNow(ctrl, player);
            res.addProperty("autoTorch", ctrl.autoTorch);
            res.addProperty("placedNow", placed);
            return res;
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
}
