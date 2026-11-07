package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.ChunkCacheContext;
import ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.*;

public class FlightPathfinder {
    // Baritone-inspired multi-coefficient backoff weights for partial path selection
    private static final double[] BARITONE_COEFFICIENTS = new double[]{1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};
    private static final double MIN_IMPROVEMENT = 0.01;
    /** Baritone's MIN_DIST_PATH: a partial route shorter than this is not useful. */
    private static final double MIN_PARTIAL_DIST = 5.0;
    private static final int MAX_EXPANSIONS = 1400;
    /** Per-plan wall-clock budget; the server tick must stay responsive. */
    private static final long SEARCH_BUDGET_NANOS = 4_000_000L;
    /** Theta* shortcuts are local corrections, not long-range raycasts. */
    private static final double MAX_THETA_DIST_SQ = 64.0;
    private static final int SMOOTH_LOOKAHEAD = 16;

    @RequiredArgsConstructor
    static class AStarNode implements Comparable<AStarNode> {
        final int x;
        final int y;
        final int z;
        final double g;
        final double h;
        final double f;
        final boolean requiresDig;
        final AStarNode parent;

        @Override
        public int compareTo(AStarNode o) {
            return Double.compare(this.f, o.f);
        }
    }

    private final DroneLogger logger;

    public FlightPathfinder(DroneLogger logger) {
        this.logger = logger;
    }

    public void clearNavigation(NavigationState state) {
        if (state == null) return;
        state.clearPath();
    }

    public static long packBlockPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    public static void setDroneFlightTarget(Drone d, double tx, double ty, double tz, float accel) {
        d.targetX_$eq((float) tx);
        d.targetY_$eq((float) ty);
        d.targetZ_$eq((float) tz);
        d.targetAcceleration_$eq(accel);
    }

    public static boolean isFlightLineClear(World world, double sx, double sy, double sz, double gx, double gy, double gz) {
        return VoxelRaycaster3D.isFatLineClear(world, sx, sy, sz, gx, gy, gz);
    }

    public static int[] findNearestPassableVoxel(World world, int cx, int cy, int cz, int maxRadius) {
        if (ItemClassifier.isPassableVoxel(world, cx, cy, cz)) return new int[]{cx, cy, cz};
        for (int r = 1; r <= maxRadius; r++) {
            int[] best = null;
            int bestDistSq = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = cx + dx;
                        int y = cy + dy;
                        int z = cz + dz;
                        if (ItemClassifier.isPassableVoxel(world, x, y, z)) {
                            int dSq = dx * dx + dy * dy + dz * dz;
                            if (dSq < bestDistSq) {
                                bestDistSq = dSq;
                                best = new int[]{x, y, z};
                            }
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    static long pathSignature(double sx, double sy, double sz, double gx, double gy, double gz) {
        // 6 coords x 26 bits do not fit in one long; mix instead of truncating to
        // 10 bits (0x3FF), which aliased goals 1024 blocks apart and reused stale
        // waypoints across the world.
        long h = 1125899906842597L;
        int[] c = new int[]{
                MathHelper.floor_double(sx), MathHelper.floor_double(sy), MathHelper.floor_double(sz),
                MathHelper.floor_double(gx), MathHelper.floor_double(gy), MathHelper.floor_double(gz)
        };
        for (int v : c) {
            h = (h ^ (v & 0xFFFFFFFFL)) * 1099511628211L;
            h = h ^ (h >>> 29);
        }
        return h;
    }

    public List<double[]> findFlightPath(World world, DroneController ctrl, NavigationState nav,
                                          double sx, double sy, double sz,
                                          double gx, double gy, double gz) {
        List<double[]> result = new ArrayList<>(4);
        if (isFlightLineClear(world, sx, sy, sz, gx, gy, gz)) {
            result.add(new double[]{gx, gy, gz});
            if (nav != null) {
                nav.lastPathNodesExpanded = 1;
                nav.lastPathPartial = false;
            }
            return result;
        }

        // Path-cache reuse: if the pilot re-planned against a goal bucket we already solved
        // within the last 60 ticks and the runner is still near the previous start cell, skip A*.
        if (nav != null && nav.cachedPathSignature != Long.MIN_VALUE && nav.pathWaypoints.size() > 1) {
            long sig = pathSignature(sx, sy, sz, gx, gy, gz);
            if (sig == nav.cachedPathSignature && nav.ticks - nav.cachedPathCreatedTick < 60) {
                nav.lastPathNodesExpanded = 0; // cache hit: zero expansion cost
                return new ArrayList<>(nav.pathWaypoints);
            }
        }

        int startX = MathHelper.floor_double(sx);
        int startY = MathHelper.floor_double(sy);
        int startZ = MathHelper.floor_double(sz);
        int goalX = MathHelper.floor_double(gx);
        int goalY = MathHelper.floor_double(gy);
        int goalZ = MathHelper.floor_double(gz);

        ChunkCacheContext bsi = new ChunkCacheContext(world, startX, startZ, 3);

        if (!bsi.isPassable(goalX, goalY, goalZ)) {
            int[] openGoal = findNearestPassableVoxel(world, goalX, goalY, goalZ, 3);
            if (openGoal != null) {
                goalX = openGoal[0];
                goalY = openGoal[1];
                goalZ = openGoal[2];
                gx = goalX + 0.5;
                gy = goalY + 0.65;
                gz = goalZ + 0.5;
            }
        }

        PriorityQueue<AStarNode> open = new PriorityQueue<>();
        Map<Long, Double> bestG = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        double h0 = Math.sqrt(
                (goalX - startX) * (goalX - startX)
                        + (goalY - startY) * (goalY - startY)
                        + (goalZ - startZ) * (goalZ - startZ)
        );
        AStarNode startNode = new AStarNode(startX, startY, startZ, 0.0, h0, h0, false, null);
        open.add(startNode);
        bestG.put(packBlockPos(startX, startY, startZ), 0.0);

        // Baritone multi-coefficient backoff tracking
        AStarNode[] bestSoFar = new AStarNode[BARITONE_COEFFICIENTS.length];
        double[] bestHeuristicSoFar = new double[BARITONE_COEFFICIENTS.length];
        for (int i = 0; i < BARITONE_COEFFICIENTS.length; i++) {
            bestSoFar[i] = startNode;
            bestHeuristicSoFar[i] = h0;
        }

        AStarNode reached = null;
        int expanded = 0;
        long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;

        while (!open.isEmpty() && expanded < MAX_EXPANSIONS && System.nanoTime() < deadline) {
            AStarNode cur = open.poll();
            long cKey = packBlockPos(cur.x, cur.y, cur.z);
            if (!closed.add(cKey)) continue;
            expanded++;

            if (cur.x == goalX && cur.y == goalY && cur.z == goalZ) {
                reached = cur;
                break;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = cur.x + dx;
                        int ny = cur.y + dy;
                        int nz = cur.z + dz;
                        long nKey = packBlockPos(nx, ny, nz);
                        if (closed.contains(nKey)) continue;
                        if (!bsi.isPassable(nx, ny, nz)) continue;
                        // Flight corridor needs the whole body inside free voxels: drone's bottom is at the
                        // node y but its AABB spans into ny+1 (blocks at head height would clip a 100% walkable path)
                        if (!bsi.isPassable(nx, ny + 1, nz)) continue;

                        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        // Anti-corner-cut check must include the headroom layer: the drone's
                        // body occupies (nx,ny+1) and (cur+off,ny+1) faces while traversing,
                        // but previously only the feet layer was checked — diagonal moves
                        // could clip solid head-block corners and then velocity-block.
                        if (manhattan >= 2) {
                            if (dx != 0 && !bsi.isPassable(cur.x + dx, cur.y, cur.z)) continue;
                            if (dy != 0 && !bsi.isPassable(cur.x, cur.y + dy, cur.z)) continue;
                            if (dz != 0 && !bsi.isPassable(cur.x, cur.y, cur.z + dz)) continue;
                            if (dx != 0 && !bsi.isPassable(cur.x + dx, cur.y + 1, cur.z)) continue;
                            if (dy != 0 && !bsi.isPassable(cur.x, cur.y + dy + 1, cur.z)) continue;
                            if (dz != 0 && !bsi.isPassable(cur.x, cur.y + 1, cur.z + dz)) continue;
                            if (manhattan == 3) {
                                if (!bsi.isPassable(cur.x + dx, cur.y + dy, cur.z)) continue;
                                if (!bsi.isPassable(cur.x + dx, cur.y, cur.z + dz)) continue;
                                if (!bsi.isPassable(cur.x, cur.y + dy, cur.z + dz)) continue;
                            }
                        }

                        double stepCost = manhattan == 1 ? 1.0 : (manhattan == 2 ? 1.414 : 1.732);
                        Block nb = bsi.getBlock(nx, ny, nz);
                        boolean inLiq = ItemClassifier.isLiquidBlock(nb);
                        if (inLiq) {
                            stepCost += (nb.getMaterial() == Material.lava) ? 4.0 : 1.2;
                        }
                        if (!bsi.isPassable(nx, ny - 1, nz)) {
                            stepCost += 0.35;
                        }

                        // Theta* Any-Angle Pathfinding: if cur.parent has a short
                        // direct line to this neighbour, shortcut it. Long rays
                        // made every expansion do seven DDAs and dominated the
                        // server tick, so this is deliberately local.
                        AStarNode effectiveParent = cur;
                        double ng = cur.g + stepCost;
                        if (cur.parent != null && !inLiq
                                && squaredDistance(cur.parent, nx, ny, nz) <= MAX_THETA_DIST_SQ
                                && VoxelRaycaster3D.isFatLineClear(
                                bsi,
                                cur.parent.x + 0.5, cur.parent.y + 0.65, cur.parent.z + 0.5,
                                nx + 0.5, ny + 0.65, nz + 0.5
                        )) {
                            double parentDist = Math.sqrt(
                                    (nx - cur.parent.x) * (nx - cur.parent.x)
                                            + (ny - cur.parent.y) * (ny - cur.parent.y)
                                            + (nz - cur.parent.z) * (nz - cur.parent.z)
                            ) + (!bsi.isPassable(nx, ny - 1, nz) ? 0.35 : 0.0);
                            if (cur.parent.g + parentDist <= ng) {
                                effectiveParent = cur.parent;
                                ng = cur.parent.g + parentDist;
                            }
                        }

                        Double prevG = bestG.get(nKey);
                        if (prevG == null || ng < prevG) {
                            bestG.put(nKey, ng);
                            double nh = Math.sqrt(
                                    (goalX - nx) * (goalX - nx)
                                            + (goalY - ny) * (goalY - ny)
                                            + (goalZ - nz) * (goalZ - nz)
                            );
                            AStarNode child = new AStarNode(nx, ny, nz, ng, nh, ng + nh * 1.15, false, effectiveParent);
                            open.add(child);
                            for (int i = 0; i < BARITONE_COEFFICIENTS.length; i++) {
                                double backoffMetric = nh + ng / BARITONE_COEFFICIENTS[i];
                                if (bestHeuristicSoFar[i] - backoffMetric > MIN_IMPROVEMENT) {
                                    bestHeuristicSoFar[i] = backoffMetric;
                                    bestSoFar[i] = child;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (nav != null) {
            nav.lastPathNodesExpanded = expanded;
            nav.lastPathPartial = (reached == null);
        }

        AStarNode targetNode = reached != null ? reached : bestPartialNode(bestSoFar, startX, startY, startZ);
        if (targetNode == null) {
            // Baritone refuses to hand back a route shorter than MIN_DIST_PATH.
            // Returning the start node here used to produce a one-waypoint
            // path that immediately blocked, which burned the whole recovery
            // budget on detours before digging could start.
            if (nav != null) {
                nav.lastPathNodesExpanded = expanded;
                nav.lastPathPartial = true;
            }
            return result;
        }

        List<double[]> raw = new ArrayList<>();
        AStarNode ptr = targetNode;
        while (ptr != null) {
            raw.add(new double[]{ptr.x + 0.5, ptr.y + 0.65, ptr.z + 0.5});
            ptr = ptr.parent;
        }
        Collections.reverse(raw);
        if (reached != null) {
            raw.add(new double[]{gx, gy, gz});
        }

        if (raw.size() <= 2) {
            result.addAll(raw);
        } else {
            int idx = 0;
            while (idx < raw.size() - 1) {
                int furthest = idx + 1;
                double[] from = idx == 0 ? new double[]{sx, sy, sz} : raw.get(idx);
                int limit = Math.min(raw.size() - 1, idx + SMOOTH_LOOKAHEAD);
                for (int probe = limit; probe > idx + 1; probe--) {
                    double[] cand = raw.get(probe);
                    if (VoxelRaycaster3D.isFatLineClear(bsi,
                            from[0], from[1], from[2], cand[0], cand[1], cand[2])) {
                        furthest = probe;
                        break;
                    }
                }
                result.add(raw.get(furthest));
                idx = furthest;
            }
        }

        if (result.size() == 1 && raw.size() == 1) {
            // A partial path that never left the start cell is not progress.
            result.clear();
            if (nav != null) nav.lastPathPartial = true;
            return result;
        }
        if (result.isEmpty()) {
            result.add(new double[]{gx, gy, gz});
        }
        if (expanded > 3 && ctrl != null && nav != null) {
            // Throttle PATH spam like STUCK: same start/goal/outcome = once per 240 ticks.
            String pathLogKey = ctrl.id + "|path:" + startX + "," + startY + "," + startZ + "->" + goalX + "," + goalY + "," + goalZ + "|" + (reached != null ? "C" : "P");
            if (!pathLogKey.equals(nav.lastPathLogKey) || nav.ticks - nav.lastPathLogTick >= 240) {
                this.logger.log("PATH", ctrl,
                        String.format("3D A* (%d,%d,%d)->(%d,%d,%d) %s",
                                startX, startY, startZ, goalX, goalY, goalZ, reached != null ? "COMPLETE" : "PARTIAL"),
                        "expanded=" + expanded + " waypoints=" + result.size());
                nav.lastPathLogKey = pathLogKey;
                nav.lastPathLogTick = nav.ticks;
            }
        }
        if (nav != null) {
            nav.cachedPathSignature = pathSignature(sx, sy, sz, gx, gy, gz);
            nav.cachedPathCreatedTick = nav.ticks;
        }
        return result;
    }

    /**
     * Baritone's {@code bestSoFar} selection: the first coefficient whose
     * furthest node is at least {@link #MIN_PARTIAL_DIST} blocks from the
     * start. Never returns the start node.
     */
    private static AStarNode bestPartialNode(AStarNode[] bestSoFar,
                                             int startX, int startY, int startZ) {
        double minSq = MIN_PARTIAL_DIST * MIN_PARTIAL_DIST;
        AStarNode firstUseful = null;
        for (AStarNode cand : bestSoFar) {
            if (cand == null) continue;
            double distSq = squaredDistance(cand, startX, startY, startZ);
            if (distSq >= minSq && firstUseful == null) firstUseful = cand;
        }
        return firstUseful;
    }

    private static double squaredDistance(AStarNode node, int x, int y, int z) {
        double dx = node.x - x;
        double dy = node.y - y;
        double dz = node.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static String describeNearestObstruction(World world, double x, double y, double z) {
        return DronePhysicsService.describeNearestObstruction(world, x, y, z);
    }

    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride) {
        DronePhysicsService.runServerSidePhysicsStep(d, maxVelOverride);
    }

    public static double[] findBlockStandoff(World world, Drone d, int bx, int by, int bz) {
        return DronePhysicsService.findBlockStandoff(world, d, bx, by, bz);
    }


}
