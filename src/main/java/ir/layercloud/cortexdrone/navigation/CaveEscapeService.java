package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
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
                if (bsi.isPassable(cur.x, cur.y, cur.z) && world.func_72937_j(cur.x, cur.y, cur.z)) {
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
                    float hardness = b != null ? b.func_149712_f(world, nx, ny, nz) : 1.5f;
                    if (hardness < 0.0f || hardness > 30.0f) continue;
                    stepCost = 4.0 + hardness * 2.5;
                    dig = true;
                }

                double ng = cur.g + stepCost;
                Double prevG = bestG.get(nKey);
                if (prevG == null || ng < prevG.doubleValue()) {
                    bestG.put(nKey, ng);
                    double nh = toOpenSky
                        ? Math.max(0.0, (world.func_72976_f(nx, nz) - ny) * 1.2)
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
        World world = ctrl.drone.field_70170_p;
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
                if (stopAtSky && (pt[3] == 1 || world.func_72937_j(pt[0], pt[1], pt[2]))) {
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

    public int escapeCaveOrDigOut(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null) return 0;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        int sx = MathHelper.func_76128_c(d.field_70165_t);
        int sy = MathHelper.func_76128_c(d.field_70163_u);
        int sz = MathHelper.func_76128_c(d.field_70161_v);

        if (!ctrl.navigationHistory.isEmpty() && ctrl.stuckTicks < 10) {
            int retraced = retraceBreadcrumbs(ctrl, 48, true, false);
            if (retraced > 1) {
                return retraced;
            }
        }

        int gx = owner != null ? MathHelper.func_76128_c(owner.field_70165_t) : sx;
        int gy = owner != null ? MathHelper.func_76128_c(owner.field_70163_u + 1.5) : world.func_72976_f(sx, sz) + 2;
        int gz = owner != null ? MathHelper.func_76128_c(owner.field_70161_v) : sz;

        List<int[]> digPath = findDigEscapePath(world, ctrl, sx, sy, sz, gx, gy, gz, true);
        int dug = 0;
        int queuedDigs = 0;
        List<double[]> escapeWps = new ArrayList<double[]>();
        for (int[] step : digPath) {
            int bx = step[0], by = step[1], bz = step[2];
            if (step[3] == 1 && !world.func_147437_c(bx, by, bz) && ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                if (d.func_70011_f(bx + 0.5, by + 0.5, bz + 0.5) <= 4.5 && dug < 4) {
                    Block b = world.func_147439_a(bx, by, bz);
                    int meta = world.func_72805_g(bx, by, bz);
                    List<ItemStack> drops = this.manager.getMiningService().getBlockDrops(world, b, bx, by, bz, meta, 0);
                    world.func_72926_e(2001, bx, by, bz, Block.func_149682_b(b) + (meta << 12));
                    world.func_147468_f(bx, by, bz);
                    if (drops != null) {
                        for (ItemStack drop : drops) {
                            if (drop != null && drop.field_77994_a > 0) {
                                ItemStack copy = drop.func_77946_l();
                                // Null copy guard: func_77946_l() can return null
                                // for exotic drops — insertIntoInventory would NPE.
                                if (copy == null) continue;
                                InventoryService.insertIntoInventory(d.mainInventory(), copy);
                                if (copy.field_77994_a > 0) {
                                    EntityItem ei = new EntityItem(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, copy);
                                    world.func_72838_d(ei);
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
            escapeWps.add(new double[]{bx + 0.5, by + 0.65, bz + 0.5});
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
