package ir.layercloud.cortexdrone.navigation.service;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.raytrace.ChunkCacheContext;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * 3D breadcrumb backtracking and Baritone-inspired weighted A* dig-escape service
 * for autonomously exiting underground caves and tunnels.
 */
@RequiredArgsConstructor
public class CaveEscapeService {
    private final AIDroneManager manager;
    private final DroneLogger logger;

    private static boolean hasAdjacentLiquid(ChunkCacheContext bsi, int x, int y, int z) {
        int[][] dirs = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            if (ItemClassifier.isLiquidBlock(bsi.getBlock(x + d[0], y + d[1], z + d[2]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Baritone-style weighted A* dig-pathfinder: computes a minimum-cost 6-connected path from (sx,sy,sz)
     * to open sky or (gx,gy,gz) where solid mineable blocks incur breakCost = 4.0 + hardness * 2.5.
     */
    public List<int[]> findDigEscapePath(World world, DroneController ctrl, int sx, int sy, int sz, int gx, int gy, int gz, boolean toOpenSky) {
        ChunkCacheContext bsi = new ChunkCacheContext(world, sx, sz, 2);
        PriorityQueue<FlightPathfinder.AStarNode> open = new PriorityQueue<FlightPathfinder.AStarNode>();
        Map<Long, Double> bestG = new HashMap<Long, Double>();
        Set<Long> closed = new HashSet<Long>();

        double h0 = toOpenSky ? Math.max(0, 75 - sy) : Math.sqrt((gx - sx) * (gx - sx) + (gy - sy) * (gy - sy) + (gz - sz) * (gz - sz));
        FlightPathfinder.AStarNode start = new FlightPathfinder.AStarNode(sx, sy, sz, 0.0, h0, h0, false, null);
        open.add(start);
        bestG.put(FlightPathfinder.packBlockPos(sx, sy, sz), 0.0);

        FlightPathfinder.AStarNode reached = null;
        FlightPathfinder.AStarNode bestPartial = start;
        double bestMetric = h0;
        int[][] dirs = new int[][]{{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        int expanded = 0;

        while (!open.isEmpty() && expanded < 450) {
            FlightPathfinder.AStarNode cur = open.poll();
            long cKey = FlightPathfinder.packBlockPos(cur.x, cur.y, cur.z);
            if (!closed.add(cKey)) continue;
            expanded++;

            if (toOpenSky) {
                if (bsi.isPassable(cur.x, cur.y, cur.z) && world.canBlockSeeTheSky(cur.x, cur.y, cur.z)) {
                    reached = cur;
                    break;
                }
            } else if (Math.abs(cur.x - gx) + Math.abs(cur.y - gy) + Math.abs(cur.z - gz) <= 1) {
                reached = cur;
                break;
            }

            for (int[] d : dirs) {
                int nx = cur.x + d[0];
                int ny = cur.y + d[1];
                int nz = cur.z + d[2];
                if (ny <= 2 || ny >= 253) continue;
                long nKey = FlightPathfinder.packBlockPos(nx, ny, nz);
                if (closed.contains(nKey)) continue;

                boolean passable = bsi.isPassable(nx, ny, nz);
                double stepCost;
                boolean dig = false;
                if (passable) {
                    Block b = bsi.getBlock(nx, ny, nz);
                    if (ItemClassifier.isLiquidBlock(b)) continue;
                    stepCost = 1.0;
                } else {
                    if (!ItemClassifier.isMineableBlock(world, nx, ny, nz)) continue;
                    if (hasAdjacentLiquid(bsi, nx, ny, nz)) continue;
                    Block b = bsi.getBlock(nx, ny, nz);
                    float hardness = b != null ? b.getBlockHardness(world, nx, ny, nz) : 1.5f;
                    if (hardness < 0.0f || hardness > 30.0f) continue;
                    stepCost = 4.0 + hardness * 2.5;
                    dig = true;
                }

                double ng = cur.g + stepCost;
                Double prevG = bestG.get(nKey);
                if (prevG == null || ng < prevG.doubleValue()) {
                    bestG.put(nKey, ng);
                    double nh = toOpenSky
                        ? Math.max(0.0, (world.getHeightValue(nx, nz) - ny) * 1.2)
                        : Math.sqrt((gx - nx) * (gx - nx) + (gy - ny) * (gy - ny) + (gz - nz) * (gz - nz));
                    FlightPathfinder.AStarNode child = new FlightPathfinder.AStarNode(nx, ny, nz, ng, nh, ng + nh * 1.2, dig, cur);
                    open.add(child);
                    double metric = nh + ng / 3.0;
                    if (metric < bestMetric && (nx != sx || ny != sy || nz != sz)) {
                        bestMetric = metric;
                        bestPartial = child;
                    }
                }
            }
        }

        FlightPathfinder.AStarNode end = reached != null ? reached : bestPartial;
        List<int[]> path = new ArrayList<int[]>();
        FlightPathfinder.AStarNode ptr = end;
        while (ptr != null && ptr.parent != null) {
            path.add(new int[]{ptr.x, ptr.y, ptr.z, ptr.requiresDig ? 1 : 0});
            ptr = ptr.parent;
        }
        Collections.reverse(path);
        return path;
    }

    public int retraceBreadcrumbs(DroneController ctrl, int maxSteps, boolean stopAtSky, boolean switchToGoto) {
        if (ctrl == null || ctrl.drone == null) return 0;
        World world = ctrl.drone.worldObj;
        List<int[]> hist = new ArrayList<int[]>(ctrl.navigationHistory);
        if (hist.isEmpty()) return 0;

        List<double[]> waypoints = new ArrayList<double[]>();
        int limit = Math.max(1, Math.min(hist.size(), maxSteps));
        int count = 0;
        for (int i = hist.size() - 1; i >= 0 && count < limit; i--) {
            int[] pt = hist.get(i);
            if (ItemClassifier.isPassableVoxel(world, pt[0], pt[1], pt[2])) {
                waypoints.add(new double[]{pt[0] + 0.5, pt[1] + 0.65, pt[2] + 0.5});
                count++;
                if (stopAtSky && (pt[3] == 1 || world.canBlockSeeTheSky(pt[0], pt[1], pt[2]))) {
                    break;
                }
            }
        }
        if (waypoints.isEmpty()) return 0;

        ctrl.pathWaypoints.clear();
        ctrl.pathWaypoints.addAll(waypoints);
        ctrl.pathIndex = 0;
        double[] last = waypoints.get(waypoints.size() - 1);
        ctrl.pathGoalX = last[0];
        ctrl.pathGoalY = last[1];
        ctrl.pathGoalZ = last[2];
        ctrl.repathCooldown = 35;
        if (switchToGoto && (ctrl.mode == Mode.FOLLOW || ctrl.mode == Mode.STAY || ctrl.mode == Mode.GOTO)) {
            ctrl.goalX = last[0];
            ctrl.goalY = last[1];
            ctrl.goalZ = last[2];
            this.manager.setDroneMode(ctrl, Mode.GOTO, "Retracing " + waypoints.size() + " breadcrumb(s) to exit cave");
        }
        // GUARD/PATROL/HUNT callers must not steal the mode mid-fight; they keep
        // ctrl.goal* untouched and just get the retrace waypoints.
        return waypoints.size();
    }

    /**
     * Self-rescue for a blocked waypoint: queue the solid voxels that actually
     * stop movement (the drone's own feet/head first, then the blocked waypoint)
     * and hand control to the mining engine. Unlike {@link #escapeCaveOrDigOut}
     * this cannot end in "no dig path found, keep hovering": it always has a
     * concrete block to break.
     */
    public int digOutBlockedWaypoint(DroneController ctrl, int wx, int wy, int wz) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) return 0;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        int sx = MathHelper.floor_double(d.posX);
        int sy = MathHelper.floor_double(d.posY);
        int sz = MathHelper.floor_double(d.posZ);

        int queued = 0;
        // Save the real task and run the rescue on an isolated queue. The original
        // queue is restored the moment the rescue queue empties, so a brief dig
        // cannot keep the rescue's filter/target flags alive for the whole task.
        DroneController.TaskSnapshot pendingSnapshot = null;
        if (ctrl.escapeResume == null) {
            pendingSnapshot = new DroneController.TaskSnapshot();
            pendingSnapshot.mode = ctrl.mode;
            pendingSnapshot.goalX = ctrl.goalX;
            pendingSnapshot.goalY = ctrl.goalY;
            pendingSnapshot.goalZ = ctrl.goalZ;
            pendingSnapshot.mineFilter = ctrl.mineFilter;
            pendingSnapshot.mineExactQueueOnly = ctrl.mineExactQueueOnly;
            pendingSnapshot.mineUntilFull = ctrl.mineUntilFull;
            pendingSnapshot.returnToOwnerAfterMine = ctrl.returnToOwnerAfterMine;
            pendingSnapshot.mineTargetCount = ctrl.mineTargetCount;
            pendingSnapshot.minedCount = ctrl.minedCount;
            pendingSnapshot.mineMatchedCount = ctrl.mineMatchedCount;
            pendingSnapshot.mineTarget = ctrl.currentMineTarget;
            pendingSnapshot.mineStandoff = ctrl.currentMineStandoff;
            pendingSnapshot.mineQueue = new ArrayList<int[]>(ctrl.mineQueue);
            ctrl.mineQueue.clear();
        }

        // Add in reverse priority: addFirst() puts each next call in front, so the
        // drone's own feet cell ends up first (it is the most urgent voxel).
        queued += queueDig(ctrl, world, wx, wy + 1, wz);
        queued += queueDig(ctrl, world, wx, wy, wz);
        queued += queueDig(ctrl, world, sx, sy + 1, sz);
        queued += queueDig(ctrl, world, sx, sy, sz);
        if (queued == 0) {
            if (pendingSnapshot != null) {
                ctrl.mineQueue.clear();
                ctrl.mineQueue.addAll(pendingSnapshot.mineQueue);
            }
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
        this.manager.setDroneMode(ctrl, Mode.MINE, "Dig out blocked waypoint");
        // Install the snapshot AFTER the mode change: setDroneMode clears any old
        // rescue state on a real transition, and this one belongs to the new dig.
        if (pendingSnapshot != null) {
            ctrl.escapeResume = pendingSnapshot;
        }
        return queued;
    }

    private static int queueDig(DroneController ctrl, World world, int x, int y, int z) {
        if (y <= 1 || y >= 255) return 0;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return 0;
        for (int[] p : ctrl.mineQueue) {
            if (p[0] == x && p[1] == y && p[2] == z) return 0;
        }
        ctrl.mineQueue.addFirst(new int[]{x, y, z});
        return 1;
    }

    /**
     * Explicit tunnel route used by the master navigation primitive. It plans a
     * weighted dig path to the requested destination, hands the blocking cells
     * to the single-action miner, and restores the caller's GOTO goal afterwards.
     */
    public int digTowardTarget(DroneController ctrl, int gx, int gy, int gz) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) return 0;
        if (ctrl.escapeResume != null) return 0;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        int sx = MathHelper.floor_double(d.posX);
        int sy = MathHelper.floor_double(d.posY);
        int sz = MathHelper.floor_double(d.posZ);
        List<int[]> digPath = findDigEscapePath(world, ctrl, sx, sy, sz, gx, gy, gz, false);
        if (digPath.isEmpty()) return 0;

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
        // queueDig adds at the front; walking the path backwards preserves the
        // planner's start-to-destination order in the final single-action queue.
        for (int i = digPath.size() - 1; i >= 0; i--) {
            int[] step = digPath.get(i);
            if (step[3] == 1) {
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
        this.manager.setDroneMode(ctrl, Mode.MINE, "Tunnel route: break through " + queued + " block(s) toward ("
            + gx + "," + gy + "," + gz + ")");
        ctrl.escapeResume = pending;
        this.logger.log("PATH", ctrl, "Tunnel route planned",
            "queuedDigs=" + queued + " goal=(" + gx + "," + gy + "," + gz + ")");
        return queued;
    }

    public int escapeCaveOrDigOut(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null) return 0;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        int sx = MathHelper.floor_double(d.posX);
        int sy = MathHelper.floor_double(d.posY);
        int sz = MathHelper.floor_double(d.posZ);

        if (!ctrl.navigationHistory.isEmpty() && ctrl.stuckTicks < 10) {
            int retraced = retraceBreadcrumbs(ctrl, 48, true, false);
            if (retraced > 1) {
                return retraced;
            }
        }

        int gx = owner != null ? MathHelper.floor_double(owner.posX) : sx;
        int gy = owner != null ? MathHelper.floor_double(owner.posY + 1.5) : world.getHeightValue(sx, sz) + 2;
        int gz = owner != null ? MathHelper.floor_double(owner.posZ) : sz;

        List<int[]> digPath = findDigEscapePath(world, ctrl, sx, sy, sz, gx, gy, gz, true);
        int dug = 0;
        int queuedDigs = 0;
        List<double[]> escapeWps = new ArrayList<double[]>();
        for (int[] step : digPath) {
            int bx = step[0], by = step[1], bz = step[2];
            if (step[3] == 1 && !world.isAirBlock(bx, by, bz) && ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                if (d.getDistance(bx + 0.5, by + 0.5, bz + 0.5) <= 4.5 && dug < 4) {
                    Block b = world.getBlock(bx, by, bz);
                    int meta = world.getBlockMetadata(bx, by, bz);
                    List<ItemStack> drops = this.manager.getBlockActionService().getBlockDrops(world, b, bx, by, bz, meta, 0);
                    world.playAuxSFX(2001, bx, by, bz, Block.getIdFromBlock(b) + (meta << 12));
                    world.setBlockToAir(bx, by, bz);
                    if (drops != null) {
                        for (ItemStack drop : drops) {
                            if (drop != null && drop.stackSize > 0) {
                                ItemStack copy = drop.copy();
                                // Null copy guard: copy() can return null
                                // for exotic drops — insertIntoInventory would NPE.
                                if (copy == null) continue;
                                InventoryService.insertIntoInventory(d.mainInventory(), copy);
                                if (copy.stackSize > 0) {
                                    EntityItem ei = new EntityItem(world, d.posX, d.posY, d.posZ, copy);
                                    world.spawnEntityInWorld(ei);
                                }
                            }
                        }
                    }
                    dug++;
                } else {
                    // Duplicate guard: repeated dig-escape escalations re-enter here
                    // while the same frontier cells are still queued; without dedup
                    // the queue grows unboundedly and the drone retargets the same
                    // buried cell repeatedly instead of finishing the tunnel.
                    boolean dup = false;
                    for (int[] p : ctrl.mineQueue) {
                        if (p[0] == bx && p[1] == by && p[2] == bz) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        ctrl.mineQueue.addFirst(new int[]{bx, by, bz});
                        queuedDigs++;
                    }
                }
            }
            if (step[3] == 0 && ItemClassifier.isPassableVoxel(world, bx, by, bz)) {
                escapeWps.add(new double[]{bx + 0.5, by + 0.65, bz + 0.5});
            }
        }
        if (queuedDigs > 0) {
            // GUARD/FOLLOW/GOTO never drain mineQueue -> the escape stalls forever in front of the
            // frontier block. The mining engine owns the whole break-your-way-out loop (standoff
            // selection, tunneling through obstruction, progress). Hand it the wheel.
            ctrl.mineFilter = "all";
            ctrl.mineTargetCount = 0;
            ctrl.returnToOwnerAfterMine = false;
            ctrl.mineUntilFull = false;
            // mineExactQueueOnly=true so MiningService drains ONLY this dig tunnel
            // (no neighbor auto-propagation, no distant auto-queue): the dig must
            // finish its ordered tunnel before any surface goal resumes. Order is
            // preserved by the firstStep lift below (nearest frontier first).
            ctrl.mineExactQueueOnly = true;
            // Prepend dedup: addFirst in path order reverses the dig sequence, so
            // the NEAREST blocking block was queued last and MINE picked farthest first.
            int[] firstStep = digPath.isEmpty() ? null : digPath.get(0);
            if (firstStep != null) {
                int[] saved = ctrl.currentMineTarget;
                for (int[] p : ctrl.mineQueue) {
                    if (p[0] == firstStep[0] && p[1] == firstStep[1] && p[2] == firstStep[2]) {
                        ctrl.mineQueue.remove(p);
                        ctrl.mineQueue.addFirst(p);
                        break;
                    }
                }
                ctrl.currentMineTarget = saved;
            }
            this.manager.setDroneMode(ctrl, Mode.MINE, "Baritone dig-escape: break through " + queuedDigs + " blocking block(s)");
            this.logger.log("ESCAPE", ctrl, "Transferred dig-escape to MINE mode", "queuedDigs=" + queuedDigs);
        }
        if (!escapeWps.isEmpty()) {
            if (queuedDigs == 0) {
                ctrl.pathWaypoints.clear();
                ctrl.pathWaypoints.addAll(escapeWps);
                ctrl.pathIndex = 0;
                ctrl.repathCooldown = 16;
                this.logger.log("PATH", ctrl, "Executed Baritone dig-escape", "dugNow=" + dug + " steps=" + escapeWps.size());
            } else {
                this.logger.log("PATH", ctrl, "Baritone dig-escape queued for mining", "dugNow=" + dug + " queuedDigs=" + queuedDigs);
            }
        }
        return escapeWps.size();
    }
}
