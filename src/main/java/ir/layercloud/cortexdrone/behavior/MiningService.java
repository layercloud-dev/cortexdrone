package ir.layercloud.cortexdrone.behavior;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import lombok.Getter;

public class MiningService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final WorldScanner worldScanner;
    private final InventoryService inventoryService;
    @Getter
    private final FarmingService farmingService;
    @Getter
    private final ExcavationPlanner excavationPlanner;
    private Method blockGetDropsMethod = null;

    public MiningService(
        AIDroneManager manager,
        DroneLogger logger,
        FlightPathfinder pathfinder,
        WorldScanner worldScanner,
        InventoryService inventoryService
    ) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.worldScanner = worldScanner;
        this.inventoryService = inventoryService;
        this.farmingService = new FarmingService(manager, logger, worldScanner, inventoryService);
        this.excavationPlanner = new ExcavationPlanner(manager, logger, worldScanner);
        try {
            this.blockGetDropsMethod = Block.class.getMethod(
                "getDrops", World.class, int.class, int.class, int.class, int.class, int.class
            );
        } catch (Throwable ignored) {
        }
    }



    public static boolean isNumericFilter(String f) {
        if (f == null || f.isEmpty()) return false;
        try {
            int n = Integer.parseInt(f);
            return n >= 1 && n <= 16;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean hasPassableHeadroom(World world, double[] st) {
        if (st == null || st.length < 3) return false;
        int bx = MathHelper.func_76128_c(st[0]);
        int by = MathHelper.func_76128_c(st[1]);
        int bz = MathHelper.func_76128_c(st[2]);
        return ItemClassifier.isPassableVoxel(world, bx, by, bz)
            && ItemClassifier.isPassableVoxel(world, bx, by + 1, bz);
    }

    private static boolean hasPassableNeighbor(World world, int x, int y, int z) {
        return ItemClassifier.isPassableVoxel(world, x + 1, y, z)
            || ItemClassifier.isPassableVoxel(world, x - 1, y, z)
            || ItemClassifier.isPassableVoxel(world, x, y + 1, z)
            || ItemClassifier.isPassableVoxel(world, x, y - 1, z)
            || ItemClassifier.isPassableVoxel(world, x, y, z + 1)
            || ItemClassifier.isPassableVoxel(world, x, y, z - 1);
    }

    @SuppressWarnings("unchecked")
    public List<ItemStack> getBlockDrops(World world, Block block, int x, int y, int z, int meta, int fortune) {
        List<ItemStack> drops = new ArrayList<ItemStack>();
        if (this.blockGetDropsMethod != null) {
            try {
                Object res = this.blockGetDropsMethod.invoke(block, world, x, y, z, meta, fortune);
                if (res instanceof List) {
                    drops.addAll((List<ItemStack>) res);
                }
            } catch (Throwable ignored) {
            }
        }
        if (drops.isEmpty()) {
            int count = Math.max(1, block.func_149745_a(world.field_73012_v));
            Item item = block.func_149650_a(meta, world.field_73012_v, fortune);
            if (item == null) {
                item = Item.func_150898_a(block);
            }
            if (item != null) {
                drops.add(new ItemStack(item, count, block.func_149692_a(meta)));
            }
        }
        return drops;
    }

    public void tickMiningBehavior(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        d.lightColor_$eq(0x00FF66);

        if (ctrl.mineTargetCount > 0 && ctrl.minedCount >= ctrl.mineTargetCount) {
            if (ctrl.currentMineTarget != null) {
                world.func_147443_d(d.func_145782_y(), ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2], -1);
            }
            ctrl.mineQueue.clear();
            ctrl.currentMineTarget = null;
            ctrl.currentMineStandoff = null;
            boolean deliverBack = !ctrl.isScriptRunning() && (ctrl.returnToOwnerAfterMine || ctrl.mineUntilFull);
            ctrl.returnToOwnerAfterMine = false;
            ctrl.mineUntilFull = false;
            ctrl.mineTargetCount = 0;
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Reached target of " + ctrl.minedCount + " harvested block(s)! "
                    + (deliverBack ? "Flying back to deliver your items." : "Returning to follow mode."));
            }
            this.manager.setDroneMode(ctrl, deliverBack ? Mode.DROP : Mode.FOLLOW, "Reached mineTargetCount");
            return;
        }

        if (InventoryService.isInventoryFull(d.mainInventory())) {
            ctrl.currentMineTarget = null;
            ctrl.currentMineStandoff = null;
            if (ctrl.mineUntilFull) {
                ctrl.mineQueue.clear();
                ctrl.mineUntilFull = false;
                ctrl.mineTargetCount = 0;
                if (!ctrl.isScriptRunning() && owner != null) {
                    this.manager.droneSay(owner, ctrl, "Inventory is full (8/8 slots, " + InventoryService.countInventoryItems(d.mainInventory()) + " items)! Flying back to deliver to you now.");
                }
                this.logger.log("MINE", ctrl, "Inventory full (mineUntilFull=true)", "harvested=" + ctrl.minedCount + " scriptRunning=" + ctrl.isScriptRunning());
                this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "Completed mineUntilFull");
                return;
            }
            TileEntity nearbyChest = this.inventoryService.findNearestInventoryWithSpace(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 20, d.mainInventory());
            if (nearbyChest != null) {
                if (owner != null) {
                    this.manager.droneSay(owner, ctrl, "Inventory full! Depositing into nearby chest and resuming (" + ctrl.mineQueue.size() + " blocks left).");
                }
                this.logger.log("MINE", ctrl, "Inventory full; auto-depositing into nearby chest", "remainingQueue=" + ctrl.mineQueue.size());
                this.manager.setDroneMode(ctrl, Mode.DEPOSIT, "Auto-deposit during mining");
            } else {
                if (owner != null) {
                    this.manager.droneSay(owner, ctrl, "Inventory full (8/8 slots)! Delivering items to you and resuming (" + ctrl.mineQueue.size() + " blocks left).");
                }
                this.logger.log("MINE", ctrl, "Inventory full; delivering to owner", "remainingQueue=" + ctrl.mineQueue.size());
                this.manager.setDroneMode(ctrl, Mode.DROP, "Auto-drop to owner during mining");
            }
            return;
        }

        if (ctrl.currentMineTarget == null && !ctrl.mineQueue.isEmpty()) {
            Iterator<int[]> qIt = ctrl.mineQueue.iterator();
            while (qIt.hasNext()) {
                int[] p = qIt.next();
                // A temporarily unloaded chunk must NOT purge its queued targets:
                // doing so silently truncates a mine task ("damaged logic") as soon
                // as the drone crosses a chunk border. Keep them until loaded.
                if (!world.func_72863_F().func_73149_a(p[0] >> 4, p[2] >> 4)) {
                    continue;
                }
                if (!ItemClassifier.isMineableBlock(world, p[0], p[1], p[2])) {
                    qIt.remove();
                }
            }

            int[] bestExposed = null;
            double[] bestStandoff = null;
            double bestScore = Double.MAX_VALUE;
            int highestY = Integer.MIN_VALUE;
            for (int[] p : ctrl.mineQueue) {
                if (p[1] > highestY) highestY = p[1];
            }

            int curBx = MathHelper.func_76128_c(d.field_70165_t);
            int curBy = MathHelper.func_76128_c(d.field_70163_u);
            int curBz = MathHelper.func_76128_c(d.field_70161_v);
            int topCur = world.func_72976_f(curBx, curBz);
            boolean droneInOpenSky = world.func_72937_j(curBx, curBy, curBz) || d.field_70163_u >= topCur - 1;
            // Surface digs are ordered top-down; underground work is ordered by
            // proximity to the drone so a vertical shaft is consumed from the
            // current level instead of the drone targeting the far surface lip.
            int refY = droneInOpenSky ? highestY : curBy;

            int checked = 0;
            int scanned = 0;
            for (int[] p : ctrl.mineQueue) {
                if (++scanned > 256) break;
                double[] st = FlightPathfinder.findMiningStandoff(world, d, p[0], p[1], p[2]);
                if (st != null) {
                    double dist = d.func_70011_f(st[0], st[1], st[2]);
                    boolean losKnown = checked < 32 && dist <= 18.0;
                    boolean hasLos = losKnown && FlightPathfinder.isFlightLineClear(
                        world, d.field_70165_t, d.field_70163_u, d.field_70161_v, st[0], st[1], st[2]);
                    if (losKnown) checked++;
                    // An exposed cell is not reachable just because it has air next
                    // to it. Underground, a target 20 blocks up with no line of sight
                    // can only be reached by tunnelling: skip it here so the
                    // tunnel branch below picks the nearest real obstruction.
                    if (!droneInOpenSky && losKnown && !hasLos && Math.abs(p[1] - curBy) > 4) {
                        continue;
                    }
                    double layerPenalty = ctrl.mineExactQueueOnly ? Math.abs(refY - p[1]) * 3.0 : 0.0;
                    double losBonus = hasLos ? -40.0 : 0.0;
                    double score = dist + layerPenalty + losBonus;
                    if (score < bestScore) {
                        bestScore = score;
                        bestExposed = p;
                        bestStandoff = st;
                    }
                }
            }

            if (bestExposed != null) {
                ctrl.mineQueue.remove(bestExposed);
                ctrl.currentMineTarget = bestExposed;
                ctrl.currentMineStandoff = bestStandoff;
                ctrl.mineProgressTicks = 0;
                ctrl.stuckTicks = 0;
                ctrl.pathWaypoints.clear();
                Block cb = world.func_147439_a(bestExposed[0], bestExposed[1], bestExposed[2]);
                int cbMeta = world.func_72805_g(bestExposed[0], bestExposed[1], bestExposed[2]);
                this.logger.log("MINE", ctrl,
                    String.format("Selected mine target %s at (%d,%d,%d)",
                        cb != null ? ItemClassifier.getBlockDisplayName(cb, cbMeta) : "block", bestExposed[0], bestExposed[1], bestExposed[2]),
                    String.format("standoff=(%.1f,%.1f,%.1f) queue=%d", bestStandoff[0], bestStandoff[1], bestStandoff[2], ctrl.mineQueue.size()));
            } else if (!ctrl.mineQueue.isEmpty()) {
                int[] nearestBuried = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (int[] p : ctrl.mineQueue) {
                    double dSq = d.func_70092_e(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                    if (dSq < nearestDistSq) {
                        nearestDistSq = dSq;
                        nearestBuried = p;
                    }
                }
                if (nearestBuried != null) {
                    int[] tunnelBlock = FlightPathfinder.findBlockingTunnelBlock(world, d, nearestBuried[0], nearestBuried[1], nearestBuried[2]);
                    if (tunnelBlock != null) {
                        ctrl.currentMineTarget = tunnelBlock;
                        ctrl.currentMineStandoff = FlightPathfinder.findMiningStandoff(world, d, tunnelBlock[0], tunnelBlock[1], tunnelBlock[2]);
                        if (ctrl.currentMineStandoff == null) {
                            ctrl.currentMineStandoff = new double[]{tunnelBlock[0] + 0.5, tunnelBlock[1] + 0.5, tunnelBlock[2] + 0.5};
                        }
                        ctrl.mineProgressTicks = 0;
                        ctrl.stuckTicks = 0;
                        ctrl.pathWaypoints.clear();
                        Block tb = world.func_147439_a(tunnelBlock[0], tunnelBlock[1], tunnelBlock[2]);
                        int tbMeta = world.func_72805_g(tunnelBlock[0], tunnelBlock[1], tunnelBlock[2]);
                        this.logger.log("MINE", ctrl,
                            String.format("Tunneling through %s at (%d,%d,%d) to reach buried (%d,%d,%d)",
                                tb != null ? ItemClassifier.getBlockDisplayName(tb, tbMeta) : "block",
                                tunnelBlock[0], tunnelBlock[1], tunnelBlock[2],
                                nearestBuried[0], nearestBuried[1], nearestBuried[2]),
                            "queue=" + ctrl.mineQueue.size());
                    } else {
                        ctrl.mineQueue.remove(nearestBuried);
                        ctrl.currentMineTarget = nearestBuried;
                        int[] nearAir = FlightPathfinder.findNearestPassableVoxel(world, nearestBuried[0], nearestBuried[1], nearestBuried[2], 6);
                        ctrl.currentMineStandoff = nearAir != null
                            ? new double[]{nearAir[0] + 0.5, nearAir[1] + 0.35, nearAir[2] + 0.5}
                            : new double[]{nearestBuried[0] + 0.5, nearestBuried[1] + 1.0, nearestBuried[2] + 0.5};
                        ctrl.mineProgressTicks = 0;
                        ctrl.stuckTicks = 0;
                        ctrl.pathWaypoints.clear();
                    }
                }
            }
        }

        if (ctrl.currentMineTarget == null) {
            if (!ctrl.mineExactQueueOnly && (ctrl.mineUntilFull || (ctrl.mineTargetCount > 0 && ctrl.minedCount < ctrl.mineTargetCount))) {
                List<int[]> more = this.worldScanner.findDistantResourceOrBiome(world, ctrl, d.field_70165_t, d.field_70163_u, d.field_70161_v, ctrl.mineFilter, 256);
                if (!more.isEmpty()) {
                    for (int[] p : more) {
                        ctrl.mineQueue.addLast(p);
                    }
                    this.logger.log("MINE", ctrl, "Auto-queued " + more.size() + " additional '" + ctrl.mineFilter + "' block(s) to continue harvesting", "minedSoFar=" + ctrl.minedCount);
                    return;
                }
            }
            boolean deliverBack = !ctrl.isScriptRunning() && (ctrl.returnToOwnerAfterMine || ctrl.mineUntilFull);
            ctrl.returnToOwnerAfterMine = false;
            ctrl.mineUntilFull = false;
            ctrl.mineTargetCount = 0;
            ctrl.replantAfterHarvest = false;
            ctrl.mineExactQueueOnly = false;
            ctrl.pendingReplants.clear();
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Finished harvesting " + ctrl.minedCount + " block(s)! "
                    + (deliverBack ? "Flying back to deliver your items." : "Returning to follow mode."));
            }
            this.logger.log("MINE", ctrl, "Mining queue completed", "harvested=" + ctrl.minedCount + " deliverBack=" + deliverBack + " scriptRunning=" + ctrl.isScriptRunning());
            this.manager.setDroneMode(ctrl, deliverBack ? Mode.DROP : Mode.FOLLOW, "Mining queue empty");
            return;
        }

        int bx = ctrl.currentMineTarget[0];
        int by = ctrl.currentMineTarget[1];
        int bz = ctrl.currentMineTarget[2];
        if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) {
            world.func_147443_d(d.func_145782_y(), bx, by, bz, -1);
            ctrl.currentMineTarget = null;
            ctrl.currentMineStandoff = null;
            return;
        }

        if (ctrl.currentMineStandoff == null || !hasPassableHeadroom(world, ctrl.currentMineStandoff)) {
            ctrl.currentMineStandoff = FlightPathfinder.findMiningStandoff(world, d, bx, by, bz);
            if (ctrl.currentMineStandoff == null) {
                // Entombed/adjacent target: the drone is inside (or flush against)
                // the block it must break, so there is no standoff with headroom.
                // Mine it from the current position instead of dropping the target
                // (this is exactly what made "dig out of the rock" stall forever).
                if (d.func_70011_f(bx + 0.5, by + 0.5, bz + 0.5) <= 2.2) {
                    ctrl.currentMineStandoff = new double[]{d.field_70165_t, d.field_70163_u, d.field_70161_v};
                } else {
                    // Buried target with no reachable standoff: this target is
                    // temporarily unreachable, but bombarding the floor with a
                    // 4-tick crack loop never yields progress. Null-guard drop +
                    // crack-reset, then let the loop pick the next queued block.
                    world.func_147443_d(d.func_145782_y(), bx, by, bz, -1);
                    ctrl.currentMineTarget = null;
                    ctrl.mineProgressTicks = 0;
                    ctrl.stuckTicks = 0;
                    return;
                }
            }
        }

        Block block = world.func_147439_a(bx, by, bz);
        if (block == null) {
            world.func_147443_d(d.func_145782_y(), bx, by, bz, -1);
            ctrl.currentMineTarget = null;
            ctrl.currentMineStandoff = null;
            ctrl.mineProgressTicks = 0;
            return;
        }
        double tx = bx + 0.5;
        double ty = by + 0.5;
        double tz = bz + 0.5;
        double sx = ctrl.currentMineStandoff[0];
        double sy = ctrl.currentMineStandoff[1];
        double sz = ctrl.currentMineStandoff[2];

        double distToBlock = d.func_70011_f(tx, ty, tz);
        double distToStandoff = d.func_70011_f(sx, sy, sz);

        boolean partialBlocked = ctrl.lastPathPartial && !ctrl.pathWaypoints.isEmpty() && ctrl.pathIndex >= ctrl.pathWaypoints.size();
        if ((ctrl.stuckTicks >= 10 || partialBlocked) && distToBlock > 3.2) {
            int[] blocking = FlightPathfinder.findBlockingTunnelBlock(world, d, bx, by, bz);
            if (blocking != null && (blocking[0] != bx || blocking[1] != by || blocking[2] != bz)) {
                world.func_147443_d(d.func_145782_y(), bx, by, bz, -1);
                // Duplicate guard: only requeue the obstructed target if it isn't
                // already queued behind the blocker; otherwise the obstructed
                // target would requeue endlessly in front of itself.
                boolean alreadyQueued = false;
                for (int[] q : ctrl.mineQueue) {
                    if (q[0] == bx && q[1] == by && q[2] == bz) {
                        alreadyQueued = true;
                        break;
                    }
                }
                if (!alreadyQueued) {
                    ctrl.mineQueue.addFirst(ctrl.currentMineTarget);
                }
                ctrl.currentMineTarget = blocking;
                ctrl.currentMineStandoff = FlightPathfinder.findMiningStandoff(world, d, blocking[0], blocking[1], blocking[2]);
                if (ctrl.currentMineStandoff == null) {
                    ctrl.currentMineStandoff = new double[]{d.field_70165_t, d.field_70163_u, d.field_70161_v};
                }
                ctrl.mineProgressTicks = 0;
                ctrl.stuckTicks = 0;
                ctrl.lastPathPartial = false;
                ctrl.pathWaypoints.clear();
                this.logger.log("MINE", ctrl,
                    String.format("Path obstructed; tunneling through (%d,%d,%d) toward (%d,%d,%d)",
                        blocking[0], blocking[1], blocking[2], bx, by, bz),
                    "");
                return;
            }
        }

        if (distToBlock <= 3.6 || distToStandoff <= 1.25) {
            FlightPathfinder.setDroneFlightTarget(d, sx, sy, sz, 0.08f);
            d.field_70159_w *= 0.4;
            d.field_70181_x *= 0.4;
            d.field_70179_y *= 0.4;

            float hardness = Math.max(0.2f, block.func_149712_f(world, bx, by, bz));
            ctrl.miningRequiredTicks = ToolInspector.computeRequiredMiningTicks(d.mainInventory(), block, hardness);
            ctrl.mineProgressTicks++;

            int meta = world.func_72805_g(bx, by, bz);
            int blockId = Block.func_149682_b(block);
            int crackStage = Math.min(9, (ctrl.mineProgressTicks * 10) / ctrl.miningRequiredTicks);
            world.func_147443_d(d.func_145782_y(), bx, by, bz, crackStage);

            if (world instanceof WorldServer && ctrl.mineProgressTicks % 2 == 0) {
                ((WorldServer) world).func_147487_a(
                    "blockcrack_" + blockId + "_" + meta,
                    tx, ty, tz, 4, 0.25, 0.25, 0.25, 0.05
                );
            }
            String blockDisp = ItemClassifier.getBlockDisplayName(block, meta);
            d.statusText_$eq("Mining " + (ctrl.mineProgressTicks * 100 / ctrl.miningRequiredTicks) + "%\n" + AIDroneManager.trimStatus(blockDisp));

            if (ctrl.mineProgressTicks >= ctrl.miningRequiredTicks) {
                world.func_147443_d(d.func_145782_y(), bx, by, bz, -1);
                int fortune = ToolInspector.getBestFortuneLevel(d.mainInventory());
                List<ItemStack> drops = getBlockDrops(world, block, bx, by, bz, meta, fortune);
                world.func_72926_e(2001, bx, by, bz, blockId + (meta << 12));
                boolean removed = world.func_147468_f(bx, by, bz);

                if (removed) {
                    ctrl.minedCount++;
                    int insertedTotal = 0;
                    IInventory inv = d.mainInventory();
                    if (drops != null) {
                        for (ItemStack drop : drops) {
                            if (drop == null || drop.field_77994_a <= 0) continue;
                            ItemStack copy = drop.func_77946_l();
                            insertedTotal += InventoryService.insertIntoInventory(inv, copy);
                            if (copy.field_77994_a > 0) {
                                EntityItem leftover = new EntityItem(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, copy);
                                leftover.field_145804_b = 0;
                                world.func_72838_d(leftover);
                            }
                        }
                    }
                    InventoryService.suckNearbyItems(d, tx, ty, tz, 3.8);
                    if (ctrl.replantAfterHarvest && ItemClassifier.isReplantableCrop(block)) {
                        this.farmingService.tryReplantAt(world, ctrl, bx, by, bz, block, meta);
                    }
                    this.logger.log("MINE", ctrl,
                        String.format("Harvested %s at (%d,%d,%d)", blockDisp, bx, by, bz),
                        "insertedItems=" + insertedTotal + " totalMined=" + ctrl.minedCount);
                    if (!ctrl.mineExactQueueOnly && !isNumericFilter(ctrl.mineFilter)) {
                        ExcavationPlanner.enqueueMatchingNeighbors(world, ctrl, bx, by, bz);
                    }
                }
                ctrl.currentMineTarget = null;
                ctrl.currentMineStandoff = null;
                ctrl.mineProgressTicks = 0;
                ctrl.stuckTicks = 0;
            }
        } else {
            this.pathfinder.navigateToward(ctrl, sx, sy, sz, 0.1f);
            d.statusText_$eq("To Ore " + (int) distToBlock + "m\n" + AIDroneManager.trimStatus(ItemClassifier.getBlockDisplayName(block, world.func_72805_g(bx, by, bz))));
        }
    }

    public int startBoxMiningTask(DroneController ctrl, EntityPlayerMP player, String anchor, int widthX, int heightY, int lengthZ) {
        return this.excavationPlanner.startBoxMiningTask(ctrl, player, anchor, widthX, heightY, lengthZ);
    }

    public int startRectMiningTask(DroneController ctrl, EntityPlayerMP player, int x1, int y1, int z1, int x2, int y2, int z2, String filter) {
        return this.excavationPlanner.startRectMiningTask(ctrl, player, x1, y1, z1, x2, y2, z2, filter);
    }

    public int startFarmingTask(DroneController ctrl, EntityPlayerMP player, String rawTarget, int radiusOverride) {
        return this.farmingService.startFarmingTask(ctrl, player, rawTarget, radiusOverride);
    }

    public int startMiningTask(DroneController ctrl, EntityPlayerMP refPlayer, String rawTarget, int maxCount, boolean untilFull) {
        return this.excavationPlanner.startMiningTask(ctrl, refPlayer, rawTarget, maxCount, untilFull);
    }
}
