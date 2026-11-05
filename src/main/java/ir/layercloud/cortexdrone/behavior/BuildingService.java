package ir.layercloud.cortexdrone.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.BuildTask;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.navigation.TspRouteOptimizer;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.StructureArchitect;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

public class BuildingService {
    // After this many consecutive placeBlock()==false on the head task, rotate it
    // to the back of the queue so one poison cell (protected, concurrently
    // claimed, or otherwise unplaceable) cannot livelock the whole build.
    private static final int BUILD_MAX_HEAD_FAILURES = 8;
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final WorldScanner worldScanner;
    private final InventoryService inventoryService;
    private final MiningService miningService;
    private final StructureArchitect structureArchitect = new StructureArchitect();

    public BuildingService(
        AIDroneManager manager,
        DroneLogger logger,
        FlightPathfinder pathfinder,
        WorldScanner worldScanner,
        InventoryService inventoryService,
        MiningService miningService
    ) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.worldScanner = worldScanner;
        this.inventoryService = inventoryService;
        this.miningService = miningService;
    }

    public StructureArchitect getStructureArchitect() {
        return this.structureArchitect;
    }

    private static int findPlacementStandoffY(World world, int x, int y, int z) {
        return ItemClassifier.isPassableVoxel(world, x, y, z) && ItemClassifier.isPassableVoxel(world, x, y + 1, z) ? y : y + 1;
    }

    private static String buildAnchorText(int remaining) {
        return remaining == 1 ? "1 left" : remaining + " left";
    }

    private static String buildStatusText(DroneController ctrl, Drone d, boolean failed) {
        int remaining = (ctrl == null) ? 0 : ctrl.buildQueue.size();
        if (failed) {
            return "Build retry\n" + buildAnchorText(remaining);
        }
        boolean prefix = ctrl != null && ctrl.mode == Mode.BUILD;
        return (prefix ? "Building\n" : "") + buildAnchorText(remaining);
    }

    private static String findFallbackConstructionMaterial(IInventory inv, String missingFilter) {
        if (inv == null) return null;
        boolean preferWood = missingFilter != null && (missingFilter.toLowerCase().contains("log")
            || missingFilter.toLowerCase().contains("wood") || missingFilter.toLowerCase().contains("plank"));
        String bestName = null;
        int bestScore = -1;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            Block b = Block.func_149634_a(st.func_77973_b());
            if (b == null || b == Blocks.field_150350_a || ItemClassifier.isLiquidBlock(b) || ItemClassifier.isOreBlock(b, st.func_77960_j())) {
                continue;
            }
            if (!b.func_149688_o().func_76220_a()) continue;
            String disp = st.func_82833_r();
            if (disp == null || disp.isEmpty()) continue;
            boolean isWood = ItemClassifier.isLogBlock(b) || ItemClassifier.hasOreDictPrefix(st, "plankWood", "logWood");
            int score = st.field_77994_a + ((preferWood == isWood) ? 100 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestName = disp;
            }
        }
        return bestName;
    }

    public boolean placeBlockFromInventory(World world, DroneController ctrl, EntityPlayerMP owner, int x, int y, int z, String filter, int metaOverride) {
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) return false;
        IInventory inv = ctrl.drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inv, filter);
        if (slot < 0) {
            int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, filter, 64);
            if (pulled > 0) {
                slot = InventoryService.findPlaceableItemSlot(inv, filter);
            }
            if (slot < 0 && owner != null) {
                int taken = this.inventoryService.takeFromPlayerInventory(ctrl, owner, filter, 64);
                if (taken > 0) {
                    slot = InventoryService.findPlaceableItemSlot(inv, filter);
                }
            }
        }
        if (slot < 0) return false;

        ItemStack st = inv.func_70301_a(slot);
        if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) return false;
        Item item = st.func_77973_b();
        Block block = Block.func_149634_a(item);
        if (block == null || block == Blocks.field_150350_a) return false;
        int meta = metaOverride >= 0 ? metaOverride : item.func_77647_b(st.func_77960_j());

        boolean placed = world.func_147465_d(x, y, z, block, meta, 3);
        if (placed) {
            world.func_72926_e(2001, x, y, z, Block.func_149682_b(block) + (meta << 12));
            inv.func_70298_a(slot, 1);
            inv.func_70296_d();
            ctrl.builtCount++;
            this.logger.log("BUILD", ctrl,
                String.format("Placed %s at (%d,%d,%d)", block.func_149732_F(), x, y, z),
                "remainingQueue=" + ctrl.buildQueue.size());
        }
        return placed;
    }

    public void tickBuildBehavior(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        if (world == null) return;
        d.lightColor_$eq(0x33AAFF);

        while (!ctrl.buildQueue.isEmpty()) {
            BuildTask peek = ctrl.buildQueue.peekFirst();
            if (peek.isReplaceExisting()) {
                if (ItemClassifier.isProtectedFixture(world, peek.getX(), peek.getY(), peek.getZ())) {
                    ctrl.buildQueue.pollFirst();
                    continue;
                }
                Block curB = world.func_147439_a(peek.getX(), peek.getY(), peek.getZ());
                int curM = world.func_72805_g(peek.getX(), peek.getY(), peek.getZ());
                if (!world.func_147437_c(peek.getX(), peek.getY(), peek.getZ()) && !ItemClassifier.isLiquidBlock(curB)) {
                    String curDisp = ItemClassifier.getBlockDisplayName(curB, curM);
                    String curId = ItemClassifier.getBlockIdentifier(curB, curM);
                    if (ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, peek.getBlockFilter())
                        && (peek.getMeta() < 0 || curM == peek.getMeta())) {
                        ctrl.buildQueue.pollFirst();
                        d.statusText_$eq(buildStatusText(ctrl, d, false));
                        continue;
                    }
                    if (peek.getTargetFilter() != null && !peek.getTargetFilter().isEmpty()
                        && !peek.getTargetFilter().equalsIgnoreCase("all") && !peek.getTargetFilter().equalsIgnoreCase("any")
                        && !ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, peek.getTargetFilter())) {
                        ctrl.buildQueue.pollFirst();
                        continue;
                    }
                }
                break;
            } else {
                if (ItemClassifier.isReplaceableBlock(world, peek.getX(), peek.getY(), peek.getZ())) {
                    break;
                }
                ctrl.buildQueue.pollFirst();
            }
        }

        if (ctrl.buildQueue.isEmpty()) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Finished building (" + ctrl.builtCount + " placed).");
            }
            this.manager.setDroneMode(ctrl, !ctrl.mineQueue.isEmpty() ? Mode.MINE : Mode.FOLLOW, "Build queue completed");
            return;
        }

        BuildTask task = ctrl.buildQueue.peekFirst();
        if (task == null) {
            this.manager.setDroneMode(ctrl, !ctrl.mineQueue.isEmpty() ? Mode.MINE : Mode.FOLLOW, "Build queue completed");
            return;
        }
        if (InventoryService.findPlaceableItemSlot(d.mainInventory(), task.getBlockFilter()) < 0) {
            int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, task.getBlockFilter(), 64);
            if (pulled == 0 && owner != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, owner, task.getBlockFilter(), 64);
            }
            // Try on-the-fly crafting (e.g., crafting planks from logs or stone bricks from stone)
            if (InventoryService.findPlaceableItemSlot(d.mainInventory(), task.getBlockFilter()) < 0) {
                this.inventoryService.craftItem(world, ctrl, owner, task.getBlockFilter(), 16);
            }
            if (InventoryService.findPlaceableItemSlot(d.mainInventory(), task.getBlockFilter()) < 0) {
                String missingFilter = task.getBlockFilter();
                // Substitute another solid building material in drone inventory if available so the structure finishes cleanly without holes!
                String fallbackMat = findFallbackConstructionMaterial(d.mainInventory(), missingFilter);
                if (fallbackMat != null) {
                    this.logger.log("BUILD", ctrl, "Substituting '" + fallbackMat + "' for exhausted '" + missingFilter + "'", "remaining=" + ctrl.buildQueue.size());
                    for (BuildTask bt : ctrl.buildQueue) {
                        if (missingFilter != null && missingFilter.equalsIgnoreCase(bt.getBlockFilter())) {
                            bt.setBlockFilter(fallbackMat);
                            bt.setMeta(-1);
                        }
                    }
                } else {
                    java.util.Iterator<BuildTask> it = ctrl.buildQueue.iterator();
                    while (it.hasNext()) {
                        BuildTask bt = it.next();
                        if (missingFilter != null && missingFilter.equalsIgnoreCase(bt.getBlockFilter())) {
                            it.remove();
                        }
                    }
                    if (ctrl.buildQueue.isEmpty()) {
                        if (!ctrl.isScriptRunning() && owner != null) {
                            this.manager.droneSay(owner, ctrl, "Ran out of '" + missingFilter + "' blocks (placed " + ctrl.builtCount + " so far)!");
                        }
                        this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Out of build materials");
                        return;
                    }
                }
            }
        }

        double[] standoff = FlightPathfinder.findMiningStandoff(world, d, task.getX(), task.getY(), task.getZ());
        double sx = standoff != null ? standoff[0] : (task.getX() + 0.5);
        double sy = standoff != null ? standoff[1] : (task.getY() + 1.35);
        double sz = standoff != null ? standoff[2] : (task.getZ() + 0.5);

        double distToBlock = d.func_70011_f(task.getX() + 0.5, task.getY() + 0.5, task.getZ() + 0.5);
        if (distToBlock <= 4.0) {
            ctrl.buildQueue.pollFirst();
            if (task.isReplaceExisting() && !ItemClassifier.isReplaceableBlock(world, task.getX(), task.getY(), task.getZ())
                && !ItemClassifier.isProtectedFixture(world, task.getX(), task.getY(), task.getZ())) {
                Block oldBlock = world.func_147439_a(task.getX(), task.getY(), task.getZ());
                int oldMeta = world.func_72805_g(task.getX(), task.getY(), task.getZ());
                int oldId = Block.func_149682_b(oldBlock);
                List<ItemStack> drops = this.miningService.getBlockDrops(world, oldBlock, task.getX(), task.getY(), task.getZ(), oldMeta, 0);
                world.func_72926_e(2001, task.getX(), task.getY(), task.getZ(), oldId + (oldMeta << 12));
                world.func_147468_f(task.getX(), task.getY(), task.getZ());
                if (drops != null) {
                    IInventory inv = d.mainInventory();
                    for (ItemStack drop : drops) {
                        if (drop == null || drop.field_77994_a <= 0) continue;
                        ItemStack copy = drop.func_77946_l();
                        InventoryService.insertIntoInventory(inv, copy);
                        if (copy.field_77994_a > 0) {
                            EntityItem leftover = new EntityItem(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, copy);
                            leftover.field_145804_b = 0;
                            world.func_72838_d(leftover);
                        }
                    }
                }
            }
            boolean placed = placeBlockFromInventory(world, ctrl, owner, task.getX(), task.getY(), task.getZ(), task.getBlockFilter(), task.getMeta());
            if (!placed) {
                ctrl.buildHeadFailures++;
                String taskKey = task.getX() + "," + task.getY() + "," + task.getZ() + "|" + task.getBlockFilter();
                // Head-of-queue invariant: the nominal cell is still empty/protected,
                // so peekFirst is unaffected. Rotation must keep OTHER tasks ahead of
                // this poison cell; addFirst would re-wedge the queue (same task, next
                // tick, zero progress). Successive failures rotate it to the back.
                if (ctrl.buildHeadFailures >= BUILD_MAX_HEAD_FAILURES) {
                    this.logger.log("BUILD", ctrl,
                        String.format("Rotating poison task %s at (%d,%d,%d) to back of queue", task.getBlockFilter(), task.getX(), task.getY(), task.getZ()),
                        "taskKey=" + taskKey + " failures=" + ctrl.buildHeadFailures + " remaining=" + ctrl.buildQueue.size());
                    ctrl.buildQueue.addLast(task);
                    ctrl.buildHeadFailures = 0;
                    ctrl.pathWaypoints.clear();
                } else {
                    ctrl.buildQueue.addFirst(task);
                }
                ctrl.stuckTicks = 0;
                // Coherent HUD: a failed placement still counts the task as remaining,
                // so BROAD truth is "Build retry / N left", not stale "To Build".
                d.statusText_$eq(buildStatusText(ctrl, d, true));
                return;
            }
            ctrl.buildHeadFailures = 0;
            d.statusText_$eq(buildStatusText(ctrl, d, false));
        } else {
            this.pathfinder.navigateToward(ctrl, sx, sy, sz, 0.1f);
            d.statusText_$eq("To Build " + (int) distToBlock + "m\n" + ctrl.buildQueue.size() + " left");
        }
    }

    public int startReplaceRoomTask(DroneController ctrl, EntityPlayerMP player, String surface, String blockFilter, String targetFilter, int meta) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        int sx = MathHelper.func_76128_c(player != null ? player.field_70165_t : d.field_70165_t);
        int sy = MathHelper.func_76128_c((player != null ? player.field_70163_u : d.field_70163_u) + 0.5);
        int sz = MathHelper.func_76128_c(player != null ? player.field_70161_v : d.field_70161_v);

        JsonObject room = this.worldScanner.scanRoom3D(world, sx, sy, sz, 18);
        String surf = (surface == null || surface.isEmpty()) ? "floor" : surface.trim().toLowerCase();
        List<JsonArray> targetArrays = new ArrayList<JsonArray>();
        if (surf.contains("all")) {
            targetArrays.add(room.getAsJsonArray("floor"));
            targetArrays.add(room.getAsJsonArray("walls"));
            targetArrays.add(room.getAsJsonArray("ceiling"));
        } else {
            if (surf.contains("floor") || surf.contains("ground")) targetArrays.add(room.getAsJsonArray("floor"));
            if (surf.contains("ceil") || surf.contains("roof")) targetArrays.add(room.getAsJsonArray("ceiling"));
            if (surf.contains("north")) targetArrays.add(room.getAsJsonArray("northWall"));
            else if (surf.contains("south")) targetArrays.add(room.getAsJsonArray("southWall"));
            else if (surf.contains("west")) targetArrays.add(room.getAsJsonArray("westWall"));
            else if (surf.contains("east")) targetArrays.add(room.getAsJsonArray("eastWall"));
            else if (surf.contains("wall")) targetArrays.add(room.getAsJsonArray("walls"));
        }
        if (targetArrays.isEmpty()) {
            targetArrays.add(room.getAsJsonArray("floor"));
        }

        List<int[]> coords = new ArrayList<int[]>();
        for (JsonArray arr : targetArrays) {
            if (arr == null) continue;
            for (JsonElement el : arr) {
                JsonArray pt = el.getAsJsonArray();
                coords.add(new int[]{pt.get(0).getAsInt(), pt.get(1).getAsInt(), pt.get(2).getAsInt()});
            }
        }
        return startReplaceBlocksTask(ctrl, player, coords, blockFilter, targetFilter, meta);
    }

    public int startReplaceBoxTask(DroneController ctrl, EntityPlayerMP player, int x1, int y1, int z1, int x2, int y2, int z2, String blockFilter, String targetFilter, int meta) {
        World world = ctrl.drone.field_70170_p;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(1, Math.min(y1, y2)), maxY = Math.min(254, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        List<int[]> coords = new ArrayList<int[]>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!ItemClassifier.isProtectedFixture(world, x, y, z)) {
                        coords.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return startReplaceBlocksTask(ctrl, player, coords, blockFilter, targetFilter, meta);
    }

    public int startReplaceBlocksTask(DroneController ctrl, EntityPlayerMP player, List<int[]> coords, String rawBlockFilter, String targetFilter, int meta) {
        final Drone d = ctrl.drone;
        World world = d.field_70170_p;
        ctrl.builtCount = 0;
        String blockFilter = ItemClassifier.resolveHeldFilter(player, rawBlockFilter);

        if (InventoryService.findPlaceableItemSlot(d.mainInventory(), blockFilter) < 0) {
            int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, blockFilter, 64);
            if (pulled == 0 && player != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, player, blockFilter, 64);
            }
        }

        Collections.sort(coords, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                double da = d.func_70092_e(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5);
                double db = d.func_70092_e(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                return Double.compare(da, db);
            }
        });

        int queued = 0;
        for (int[] p : coords) {
            if (ItemClassifier.isProtectedFixture(world, p[0], p[1], p[2])) continue;
            Block curB = world.func_147439_a(p[0], p[1], p[2]);
            int curM = world.func_72805_g(p[0], p[1], p[2]);
            if (!world.func_147437_c(p[0], p[1], p[2]) && !ItemClassifier.isLiquidBlock(curB)) {
                String curDisp = ItemClassifier.getBlockDisplayName(curB, curM);
                String curId = ItemClassifier.getBlockIdentifier(curB, curM);
                if (ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, blockFilter) && (meta < 0 || curM == meta)) {
                    continue;
                }
                if (targetFilter != null && !targetFilter.isEmpty()
                    && !targetFilter.equalsIgnoreCase("all") && !targetFilter.equalsIgnoreCase("any")
                    && !ItemClassifier.matchesBlockOrItemName(curDisp, curId, curM, targetFilter)) {
                    continue;
                }
            }
            ctrl.buildQueue.addLast(new BuildTask(p[0], p[1], p[2], blockFilter, meta, true, targetFilter));
            queued++;
        }

        if (!ctrl.buildQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.BUILD, "Queued " + queued + " replace tasks -> '" + blockFilter + "'");
            d.lightColor_$eq(0x33AAFF);
            d.statusText_$eq("Replacing\n" + queued + " blk");
        }
        return queued;
    }

    public int startBuildBoxTask(DroneController ctrl, int x1, int y1, int z1, int x2, int y2, int z2, String rawBlockFilter, int meta, boolean hollow) {
        World world = ctrl.drone.field_70170_p;
        EntityPlayerMP owner = CortexDroneMod.findPlayer(ctrl.ownerName);
        String blockFilter = ItemClassifier.resolveHeldFilter(owner, rawBlockFilter);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(1, Math.min(y1, y2)), maxY = Math.min(254, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        ctrl.builtCount = 0;

        int added = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (hollow && x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ) {
                        continue;
                    }
                    if (ItemClassifier.isReplaceableBlock(world, x, y, z)) {
                        ctrl.buildQueue.addLast(new BuildTask(x, y, z, blockFilter, meta));
                        added++;
                    }
                }
            }
        }
        if (!ctrl.buildQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.BUILD, "Queued " + added + " build tasks (" + blockFilter + ")");
        }
        return added;
    }

    public int plugLiquidsInBox(DroneController ctrl, int x1, int y1, int z1, int x2, int y2, int z2, String blockFilter) {
        World world = ctrl.drone.field_70170_p;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(1, Math.min(y1, y2)), maxY = Math.min(254, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int added = 0;
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = world.func_147439_a(x, y, z);
                    if (ItemClassifier.isLiquidBlock(b)) {
                        ctrl.buildQueue.addFirst(new BuildTask(x, y, z, blockFilter != null ? blockFilter : "any", -1));
                        added++;
                    }
                }
            }
        }
        if (added > 0) {
            this.manager.setDroneMode(ctrl, Mode.BUILD, "Plugging " + added + " liquid blocks");
        }
        return added;
    }

    public void tryPlaceAutoTorch(DroneController ctrl) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        EntityPlayerMP owner = CortexDroneMod.findPlayer(ctrl.ownerName);
        double refX = (owner != null && owner.field_70170_p == world && d.func_70032_d(owner) < 20.0)
            ? owner.field_70165_t : d.field_70165_t;
        double refY = (owner != null && owner.field_70170_p == world && d.func_70032_d(owner) < 20.0)
            ? owner.field_70163_u : d.field_70163_u;
        double refZ = (owner != null && owner.field_70170_p == world && d.func_70032_d(owner) < 20.0)
            ? owner.field_70161_v : d.field_70161_v;

        int bx = MathHelper.func_76128_c(refX);
        int by = MathHelper.func_76128_c(refY);
        int bz = MathHelper.func_76128_c(refZ);

        for (int dy = 1; dy >= -4; dy--) {
            int y = by + dy;
            if (y <= 1) continue;
            if (world.func_147437_c(bx, y, bz) && World.func_147466_a(world, bx, y - 1, bz)) {
                int light = world.func_72957_l(bx, y, bz);
                if (light <= 7) {
                    if (InventoryService.consumeItemMatching(d.mainInventory(), "torch")
                        || (owner != null && InventoryService.consumeItemMatching(owner.field_71071_by, "torch"))) {
                        Block torchBlock = Blocks.field_150478_aa != null
                            ? Blocks.field_150478_aa
                            : Block.func_149684_b("torch");
                        if (torchBlock != null) {
                            world.func_147465_d(bx, y, bz, torchBlock, 5, 3);
                            world.func_72908_a(bx + 0.5, y + 0.5, bz + 0.5, "dig.wood", 0.8f, 1.0f);
                            this.logger.log("MINE", ctrl, String.format("Auto-placed torch at (%d,%d,%d)", bx, y, bz), "prevLight=" + light);
                        }
                    }
                }
                break;
            }
        }
    }

    public boolean placeTorchNow(DroneController ctrl, EntityPlayerMP player) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        int bx = MathHelper.func_76128_c(player != null ? player.field_70165_t : d.field_70165_t);
        int by = MathHelper.func_76128_c(player != null ? player.field_70163_u : d.field_70163_u);
        int bz = MathHelper.func_76128_c(player != null ? player.field_70161_v : d.field_70161_v);

        for (int dy = 1; dy >= -4; dy--) {
            int y = by + dy;
            if (y <= 1) continue;
            if (world.func_147437_c(bx, y, bz) && World.func_147466_a(world, bx, y - 1, bz)) {
                if (InventoryService.consumeItemMatching(d.mainInventory(), "torch")
                    || (player != null && InventoryService.consumeItemMatching(player.field_71071_by, "torch"))) {
                    Block torchBlock = Blocks.field_150478_aa != null
                        ? Blocks.field_150478_aa
                        : Block.func_149684_b("torch");
                    if (torchBlock != null) {
                        world.func_147465_d(bx, y, bz, torchBlock, 5, 3);
                        world.func_72908_a(bx + 0.5, y + 0.5, bz + 0.5, "dig.wood", 0.8f, 1.0f);
                        return true;
                    }
                }
                break;
            }
        }
        return false;
    }

    public JsonObject startBuildStructureTask(DroneController ctrl, EntityPlayerMP player, JsonObject spec) {
        World world = ctrl.drone.field_70170_p;
        StructureArchitect.StructurePlan plan = this.structureArchitect.generateStructureBlueprint(world, player, ctrl, spec);
        ctrl.builtCount = 0;
        List<BuildTask> optimized = TspRouteOptimizer.optimizeBuildTasksByLayer(
            plan.getBuildTasks(),
            ctrl.drone.field_70165_t,
            ctrl.drone.field_70163_u,
            ctrl.drone.field_70161_v
        );
        for (BuildTask bt : optimized) {
            ctrl.buildQueue.addLast(bt);
        }
        if (!ctrl.buildQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.BUILD, "Building structure (" + optimized.size() + " blocks, 2-Opt layer tour)");
            ctrl.drone.lightColor_$eq(0x33AAFF);
            ctrl.drone.statusText_$eq("Building\n" + ctrl.buildQueue.size() + " blk");
        }
        return plan.toJson();
    }
}
