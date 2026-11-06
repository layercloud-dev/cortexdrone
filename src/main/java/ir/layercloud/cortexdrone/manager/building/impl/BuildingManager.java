package ir.layercloud.cortexdrone.manager.building.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.block.impl.BreakBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.PlaceBlockAction;
import ir.layercloud.cortexdrone.context.block.impl.BlockContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
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

/**
 * Long-lived building manager. It owns block break/place behavior, the mining
 * loop and block-drop calculations used by block actions and navigation
 * self-rescue. It is not an action dispatcher.
 */
public final class BuildingManager extends ActionManager<BlockContext, BlockRequest, BlockResult> {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final InventoryService inventoryService;
    private Method blockGetDropsMethod = null;

    public BuildingManager(
        AIDroneManager manager,
        DroneLogger logger,
        InventoryService inventoryService
    ) {
        super("block", "break_block", "place_block");
        this.manager = manager;
        this.logger = logger;
        this.inventoryService = inventoryService;
        try {
            this.blockGetDropsMethod = Block.class.getMethod(
                "getDrops", World.class, int.class, int.class, int.class, int.class, int.class
            );
        } catch (Throwable ignored) {
        }
    }


    private static boolean hasPassableHeadroom(World world, double[] st) {
        if (st == null || st.length < 3) return false;
        int bx = MathHelper.floor_double(st[0]);
        int by = MathHelper.floor_double(st[1]);
        int bz = MathHelper.floor_double(st[2]);
        return ItemClassifier.isPassableVoxel(world, bx, by, bz)
            && ItemClassifier.isPassableVoxel(world, bx, by + 1, bz);
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
            int count = Math.max(1, block.quantityDropped(world.rand));
            Item item = block.getItemDropped(meta, world.rand, fortune);
            if (item == null) {
                item = Item.getItemFromBlock(block);
            }
            if (item != null) {
                drops.add(new ItemStack(item, count, block.damageDropped(meta)));
            }
        }
        return drops;
    }

    public void tick(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) return;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        d.lightColor_$eq(0x00FF66);

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
            TileEntity nearbyChest = this.inventoryService.findNearestInventoryWithSpace(world, d.posX, d.posY, d.posZ, 20, d.mainInventory());
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
                if (!world.getChunkProvider().chunkExists(p[0] >> 4, p[2] >> 4)) {
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

            int curBx = MathHelper.floor_double(d.posX);
            int curBy = MathHelper.floor_double(d.posY);
            int curBz = MathHelper.floor_double(d.posZ);
            int topCur = world.getHeightValue(curBx, curBz);
            boolean droneInOpenSky = world.canBlockSeeTheSky(curBx, curBy, curBz) || d.posY >= topCur - 1;
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
                    double dist = d.getDistance(st[0], st[1], st[2]);
                    boolean losKnown = checked < 32 && dist <= 18.0;
                    boolean hasLos = losKnown && FlightPathfinder.isFlightLineClear(
                        world, d.posX, d.posY, d.posZ, st[0], st[1], st[2]);
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
                Block cb = world.getBlock(bestExposed[0], bestExposed[1], bestExposed[2]);
                int cbMeta = world.getBlockMetadata(bestExposed[0], bestExposed[1], bestExposed[2]);
                this.logger.log("MINE", ctrl,
                    String.format("Selected mine target %s at (%d,%d,%d)",
                        cb != null ? ItemClassifier.getBlockDisplayName(cb, cbMeta) : "block", bestExposed[0], bestExposed[1], bestExposed[2]),
                    String.format("standoff=(%.1f,%.1f,%.1f) queue=%d", bestStandoff[0], bestStandoff[1], bestStandoff[2], ctrl.mineQueue.size()));
            } else if (!ctrl.mineQueue.isEmpty()) {
                int[] nearestBuried = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (int[] p : ctrl.mineQueue) {
                    double dSq = d.getDistanceSq(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
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
                        Block tb = world.getBlock(tunnelBlock[0], tunnelBlock[1], tunnelBlock[2]);
                        int tbMeta = world.getBlockMetadata(tunnelBlock[0], tunnelBlock[1], tunnelBlock[2]);
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
            boolean deliverBack = !ctrl.isScriptRunning() && (ctrl.returnToOwnerAfterMine || ctrl.mineUntilFull);
            if (ctrl.escapeResume != null) {
                resumeAfterSelfRescue(ctrl);
                return;
            }
            ctrl.returnToOwnerAfterMine = false;
            ctrl.mineUntilFull = false;
            ctrl.mineTargetCount = 0;
            ctrl.mineExactQueueOnly = false;
            this.logger.log("MINE", ctrl, "Break queue completed", "harvested=" + ctrl.minedCount + " scriptRunning=" + ctrl.isScriptRunning());
            this.manager.setDroneMode(ctrl, deliverBack ? Mode.DROP : Mode.FOLLOW, "Break queue empty");
            return;
        }

        int bx = ctrl.currentMineTarget[0];
        int by = ctrl.currentMineTarget[1];
        int bz = ctrl.currentMineTarget[2];
        if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) {
            world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, -1);
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
                if (d.getDistance(bx + 0.5, by + 0.5, bz + 0.5) <= 2.2) {
                    ctrl.currentMineStandoff = new double[]{d.posX, d.posY, d.posZ};
                } else {
                    // Buried target with no reachable standoff: this target is
                    // temporarily unreachable, but bombarding the floor with a
                    // 4-tick crack loop never yields progress. Null-guard drop +
                    // crack-reset, then let the loop pick the next queued block.
                    world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, -1);
                    ctrl.currentMineTarget = null;
                    ctrl.mineProgressTicks = 0;
                    ctrl.stuckTicks = 0;
                    return;
                }
            }
        }

        Block block = world.getBlock(bx, by, bz);
        if (block == null) {
            world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, -1);
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

        double distToBlock = d.getDistance(tx, ty, tz);
        double distToStandoff = d.getDistance(sx, sy, sz);

        boolean partialBlocked = ctrl.lastPathPartial && !ctrl.pathWaypoints.isEmpty() && ctrl.pathIndex >= ctrl.pathWaypoints.size();
        if ((ctrl.stuckTicks >= 10 || partialBlocked) && distToBlock > 3.2) {
            int[] blocking = FlightPathfinder.findBlockingTunnelBlock(world, d, bx, by, bz);
            if (blocking != null && (blocking[0] != bx || blocking[1] != by || blocking[2] != bz)) {
                world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, -1);
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
                    ctrl.currentMineStandoff = new double[]{d.posX, d.posY, d.posZ};
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
            this.manager.getNavigationManager().hold(ctrl, "Mining standoff hold");

            float hardness = Math.max(0.2f, block.getBlockHardness(world, bx, by, bz));
            ctrl.miningRequiredTicks = ToolInspector.computeRequiredMiningTicks(d.mainInventory(), block, hardness);
            ctrl.mineProgressTicks++;

            int meta = world.getBlockMetadata(bx, by, bz);
            int blockId = Block.getIdFromBlock(block);
            int crackStage = Math.min(9, (ctrl.mineProgressTicks * 10) / ctrl.miningRequiredTicks);
            world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, crackStage);

            if (world instanceof WorldServer && ctrl.mineProgressTicks % 2 == 0) {
                ((WorldServer) world).func_147487_a(
                    "blockcrack_" + blockId + "_" + meta,
                    tx, ty, tz, 4, 0.25, 0.25, 0.25, 0.05
                );
            }
            String blockDisp = ItemClassifier.getBlockDisplayName(block, meta);
            d.statusText_$eq("Mining " + (ctrl.mineProgressTicks * 100 / ctrl.miningRequiredTicks) + "%\n" + AIDroneManager.trimStatus(blockDisp));

            if (ctrl.mineProgressTicks >= ctrl.miningRequiredTicks) {
                world.destroyBlockInWorldPartially(d.getEntityId(), bx, by, bz, -1);
                int fortune = ToolInspector.getBestFortuneLevel(d.mainInventory());
                List<ItemStack> drops = getBlockDrops(world, block, bx, by, bz, meta, fortune);
                world.playAuxSFX(2001, bx, by, bz, blockId + (meta << 12));
                boolean removed = world.setBlockToAir(bx, by, bz);

                if (removed) {
                    ctrl.minedCount++;
                    int insertedTotal = 0;
                    IInventory inv = d.mainInventory();
                    if (drops != null) {
                        for (ItemStack drop : drops) {
                            if (drop == null || drop.stackSize <= 0) continue;
                            ItemStack copy = drop.copy();
                            insertedTotal += InventoryService.insertIntoInventory(inv, copy);
                            if (copy.stackSize > 0) {
                                EntityItem leftover = new EntityItem(world, d.posX, d.posY, d.posZ, copy);
                                leftover.delayBeforeCanPickup = 0;
                                world.spawnEntityInWorld(leftover);
                            }
                        }
                    }
                    InventoryService.suckNearbyItems(d, tx, ty, tz, 3.8);
                    this.logger.log("MINE", ctrl,
                        String.format("Harvested %s at (%d,%d,%d)", blockDisp, bx, by, bz),
                        "insertedItems=" + insertedTotal + " totalMined=" + ctrl.minedCount);
                }
                ctrl.currentMineTarget = null;
                ctrl.currentMineStandoff = null;
                ctrl.mineProgressTicks = 0;
                ctrl.stuckTicks = 0;
            }
        } else {
            this.manager.getNavigationManager().flyToward(ctrl, sx, sy, sz, "Mining standoff");
            d.statusText_$eq("To Ore " + (int) distToBlock + "m\n" + AIDroneManager.trimStatus(ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(bx, by, bz))));
        }
    }

    /**
     * Restores the task that was interrupted by a blocked-waypoint self-rescue.
     * The rescue dig itself is deliberately not counted toward a mine target, so
     * "mine 64 iron" cannot finish early just because stone was in the way.
     */
    private void resumeAfterSelfRescue(DroneController ctrl) {
        DroneController.TaskSnapshot snap = ctrl.escapeResume;
        ctrl.escapeResume = null;
        if (snap == null) {
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Self-rescue complete");
            return;
        }
        ctrl.mineFilter = snap.mineFilter;
        ctrl.mineExactQueueOnly = snap.mineExactQueueOnly;
        ctrl.mineUntilFull = snap.mineUntilFull;
        ctrl.returnToOwnerAfterMine = snap.returnToOwnerAfterMine;
        ctrl.mineTargetCount = snap.mineTargetCount;
        ctrl.minedCount = snap.minedCount;
        ctrl.mineMatchedCount = snap.mineMatchedCount;
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = snap.mineStandoff;
        ctrl.pathWaypoints.clear();
        ctrl.pathIndex = 0;
        ctrl.pathGoalX = Double.NaN;
        ctrl.repathCooldown = 0;
        ctrl.stuckTicks = 0;

        // Restore the parked task queue, then put the interrupted current target
        // back at the front (it was removed from the queue when selected).
        ctrl.mineQueue.clear();
        if (snap.mineQueue != null) {
            ctrl.mineQueue.addAll(snap.mineQueue);
        }
        if (snap.mineTarget != null) {
            boolean duplicate = false;
            for (int[] p : ctrl.mineQueue) {
                if (p != null && p[0] == snap.mineTarget[0]
                    && p[1] == snap.mineTarget[1] && p[2] == snap.mineTarget[2]) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) ctrl.mineQueue.addFirst(snap.mineTarget);
        }

        if (snap.mode == Mode.MINE && !ctrl.mineQueue.isEmpty()) {
            this.logger.log("ESCAPE", ctrl, "Self-rescue complete; resuming mining",
                "remaining=" + ctrl.mineQueue.size() + " mined=" + ctrl.minedCount);
            this.manager.setDroneMode(ctrl, Mode.MINE, "Resumed mining after self-rescue");
            return;
        }
        if (snap.mode != null && snap.mode != Mode.MINE) {
            ctrl.goalX = snap.goalX;
            ctrl.goalY = snap.goalY;
            ctrl.goalZ = snap.goalZ;
            this.logger.log("ESCAPE", ctrl, "Self-rescue complete; resuming " + snap.mode.name(),
                String.format("goal=(%.1f,%.1f,%.1f)", ctrl.goalX, ctrl.goalY, ctrl.goalZ));
            this.manager.setDroneMode(ctrl, snap.mode, "Resumed " + snap.mode.name() + " after self-rescue");
            return;
        }
        this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Self-rescue complete");
    }

    /**
     * Starts breaking exactly one explicit block. The mining loop is advanced
     * by {@link #tick(DroneController, EntityPlayerMP)}.
     */
    public boolean startBreak(DroneController controller, int x, int y, int z) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return false;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return false;

        if (controller.currentMineTarget != null) {
            world.destroyBlockInWorldPartially(drone.getEntityId(),
                controller.currentMineTarget[0], controller.currentMineTarget[1],
                controller.currentMineTarget[2], -1);
        }
        controller.clearSelfRescue(false);
        controller.mineQueue.clear();
        controller.currentMineTarget = new int[]{x, y, z};

        double[] standoff = FlightPathfinder.findMiningStandoff(world, drone, x, y, z);
        if (standoff == null && drone.getDistance(x + 0.5, y + 0.5, z + 0.5) <= 2.2) {
            standoff = new double[]{drone.posX, drone.posY, drone.posZ};
        }
        controller.currentMineStandoff = standoff;
        controller.mineFilter = "all";
        controller.mineExactQueueOnly = true;
        controller.mineTargetCount = 0;
        controller.mineMatchedCount = 0;
        controller.minedCount = 0;
        controller.returnToOwnerAfterMine = false;
        controller.mineUntilFull = false;
        controller.mineProgressTicks = 0;
        controller.stuckTicks = 0;
        controller.lastPathPartial = false;
        controller.pathWaypoints.clear();
        controller.pathIndex = 0;
        controller.repathCooldown = 0;

        this.manager.setDroneMode(controller, Mode.MINE,
            "Master break_block (" + x + "," + y + "," + z + ")");
        drone.lightColor_$eq(0x00FF66);
        drone.statusText_$eq("Break block\n" + x + "," + y + "," + z);
        return true;
    }

    /** Places exactly one explicit block from drone/chest/player inventory. */
    public boolean placeBlock(DroneController controller, EntityPlayerMP player,
                              int x, int y, int z, String filter, int metaOverride) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return false;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) return false;

        IInventory inventory = drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inventory, filter);
        if (slot < 0) {
            int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(
                world, controller, player, filter, 64);
            if (pulled > 0) slot = InventoryService.findPlaceableItemSlot(inventory, filter);
            if (slot < 0 && player != null) {
                int taken = this.inventoryService.takeFromPlayerInventory(
                    controller, player, filter, 64);
                if (taken > 0) slot = InventoryService.findPlaceableItemSlot(inventory, filter);
            }
        }
        if (slot < 0) return false;

        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;

        Item item = stack.getItem();
        Block block = Block.getBlockFromItem(item);
        if (block == null || block == Blocks.air) return false;

        int meta = metaOverride >= 0 ? metaOverride : item.getMetadata(stack.getItemDamage());
        boolean placed = world.setBlock(x, y, z, block, meta, 3);
        if (placed) {
            world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
            inventory.decrStackSize(slot, 1);
            inventory.markDirty();
            controller.builtCount++;
            this.logger.log("BUILD", controller,
                String.format("Placed %s at (%d,%d,%d)", block.getLocalizedName(), x, y, z), "");
        }
        return placed;
    }


    /**
     * Starts a navigation-owned dig route. The building manager performs the
     * block breaking; the navigation manager keeps the session alive and
     * decides what to do when control returns to GOTO.
     */
    public int startDigRoute(DroneController ctrl, List<int[]> blocks, String label) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null
            || ctrl.escapeResume != null || blocks == null || blocks.isEmpty()) {
            return 0;
        }
        World world = ctrl.drone.worldObj;
        DroneController.TaskSnapshot pending = new DroneController.TaskSnapshot();
        pending.mode = ctrl.mode;
        pending.goalX = ctrl.goalX;
        pending.goalY = ctrl.goalY;
        pending.goalZ = ctrl.goalZ;
        pending.mineFilter = ctrl.mineFilter;
        pending.mineExactQueueOnly = ctrl.mineExactQueueOnly;
        pending.mineUntilFull = ctrl.mineUntilFull;
        pending.returnToOwnerAfterMine = ctrl.returnToOwnerAfterMine;
        pending.mineTargetCount = ctrl.mineTargetCount;
        pending.minedCount = ctrl.minedCount;
        pending.mineMatchedCount = ctrl.mineMatchedCount;
        pending.mineTarget = ctrl.currentMineTarget;
        pending.mineStandoff = ctrl.currentMineStandoff;
        pending.mineQueue = new ArrayList<int[]>(ctrl.mineQueue);
        ctrl.mineQueue.clear();

        int queued = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            int[] step = blocks.get(i);
            if (step != null && step.length > 3 && step[3] == 1) {
                queued += queueDig(ctrl, world, step[0], step[1], step[2]);
            }
        }
        if (queued == 0) {
            ctrl.mineQueue.addAll(pending.mineQueue);
            return 0;
        }

        ctrl.mineFilter = "all";
        ctrl.mineExactQueueOnly = true;
        ctrl.mineUntilFull = false;
        ctrl.mineTargetCount = 0;
        ctrl.returnToOwnerAfterMine = false;
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = null;
        ctrl.mineProgressTicks = 0;
        ctrl.stuckTicks = 0;
        ctrl.lastPathPartial = false;
        ctrl.pathWaypoints.clear();
        ctrl.pathIndex = 0;
        ctrl.repathCooldown = 0;
        this.manager.setDroneMode(ctrl, Mode.MINE, label);
        ctrl.escapeResume = pending;
        this.logger.log("PATH", ctrl, "Dig route queued",
            "queuedDigs=" + queued + " label=" + label);
        return queued;
    }

    private static int queueDig(DroneController ctrl, World world, int x, int y, int z) {
        if (y <= 1 || y >= 255) return 0;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return 0;
        for (int[] point : ctrl.mineQueue) {
            if (point[0] == x && point[1] == y && point[2] == z) return 0;
        }
        ctrl.mineQueue.addFirst(new int[]{x, y, z});
        return 1;
    }

    @Override
    protected BlockContext createContext(DroneController controller, EntityPlayerMP player,
                                         String operation) {
        return new BlockContext(controller, player, operation);
    }

    @Override
    protected Action<BlockContext> createAction(BlockContext context, String operation,
                                                BlockRequest request) {
        if ("break_block".equals(operation)) return new BreakBlockAction(this, request);
        if ("place_block".equals(operation)) return new PlaceBlockAction(this, request);
        throw new IllegalArgumentException("Unsupported block action: " + operation);
    }

    @Override
    protected BlockResult result(BlockContext context) {
        return context.result();
    }

}
