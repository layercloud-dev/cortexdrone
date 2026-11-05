package ir.layercloud.cortexdrone.navigation.service;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.raytrace.ChunkCacheContext;
import ir.layercloud.cortexdrone.navigation.raytrace.VoxelRaycaster3D;
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
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import lombok.RequiredArgsConstructor;

public class FlightPathfinder {
    // Baritone-inspired multi-coefficient backoff weights for partial path selection
    private static final double[] BARITONE_COEFFICIENTS = new double[]{1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};
    private static final double MIN_IMPROVEMENT = 0.01;

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

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final PlayerTransportService transportService;
    private final CaveEscapeService caveEscapeService;

    public FlightPathfinder(AIDroneManager manager, DroneLogger logger) {
        this.manager = manager;
        this.logger = logger;
        this.transportService = new PlayerTransportService(manager, this);
        this.caveEscapeService = new CaveEscapeService(manager, logger);
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
            MathHelper.func_76128_c(sx), MathHelper.func_76128_c(sy), MathHelper.func_76128_c(sz),
            MathHelper.func_76128_c(gx), MathHelper.func_76128_c(gy), MathHelper.func_76128_c(gz)
        };
        for (int v : c) {
            h = (h ^ (v & 0xFFFFFFFFL)) * 1099511628211L;
            h = h ^ (h >>> 29);
        }
        return h;
    }

    public List<double[]> findFlightPath(NavContext nav) {
        return findFlightPath(nav.world(), nav.controller(),
            nav.startX(), nav.startY(), nav.startZ(),
            nav.goalX(), nav.goalY(), nav.goalZ());
    }

    public List<double[]> findFlightPath(World world, DroneController ctrl, double sx, double sy, double sz, double gx, double gy, double gz) {
        List<double[]> result = new ArrayList<double[]>(4);
        if (isFlightLineClear(world, sx, sy, sz, gx, gy, gz)) {
            result.add(new double[]{gx, gy, gz});
            if (ctrl != null) {
                ctrl.lastPathNodesExpanded = 1;
                ctrl.lastPathPartial = false;
            }
            return result;
        }

        // Path-cache reuse: if navigateToward re-planned against a goal bucket we already solved
        // within the last 60 ticks and the runner is still near the previous start cell, skip A*.
        if (ctrl != null && ctrl.cachedPathSignature != Long.MIN_VALUE && ctrl.pathWaypoints.size() > 1) {
            long sig = pathSignature(sx, sy, sz, gx, gy, gz);
            if (sig == ctrl.cachedPathSignature && ctrl.tickClock - ctrl.cachedPathCreatedTick < 60) {
                ctrl.lastPathNodesExpanded = 0; // cache hit: zero expansion cost
                return new ArrayList<double[]>(ctrl.pathWaypoints);
            }
        }

        int startX = MathHelper.func_76128_c(sx);
        int startY = MathHelper.func_76128_c(sy);
        int startZ = MathHelper.func_76128_c(sz);
        int goalX = MathHelper.func_76128_c(gx);
        int goalY = MathHelper.func_76128_c(gy);
        int goalZ = MathHelper.func_76128_c(gz);

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

        PriorityQueue<AStarNode> open = new PriorityQueue<AStarNode>();
        Map<Long, Double> bestG = new HashMap<Long, Double>();
        Set<Long> closed = new HashSet<Long>();

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
        int maxExpand = 400;

        while (!open.isEmpty() && expanded < maxExpand) {
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
                            stepCost += (nb.func_149688_o() == Material.field_151587_i) ? 4.0 : 1.2;
                        }
                        if (!bsi.isPassable(nx, ny - 1, nz)) {
                            stepCost += 0.35;
                        }

                        // Theta* Any-Angle Pathfinding: if cur.parent has direct 3D DDA line-of-sight to (nx,ny,nz), shortcut directly from cur.parent
                        AStarNode effectiveParent = cur;
                        double ng = cur.g + stepCost;
                        if (cur.parent != null && !inLiq && VoxelRaycaster3D.isFatLineClear(
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
                        if (prevG == null || ng < prevG.doubleValue()) {
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

        if (ctrl != null) {
            ctrl.lastPathNodesExpanded = expanded;
            ctrl.lastPathPartial = (reached == null);
        }

        AStarNode targetNode = reached;
        if (targetNode == null) {
            // Select best Baritone backoff node that travels at least 2 blocks from start
            for (int i = 0; i < BARITONE_COEFFICIENTS.length; i++) {
                AStarNode cand = bestSoFar[i];
                if (cand != null) {
                    int distSq = (cand.x - startX) * (cand.x - startX)
                        + (cand.y - startY) * (cand.y - startY)
                        + (cand.z - startZ) * (cand.z - startZ);
                    if (distSq >= 4) {
                        targetNode = cand;
                        break;
                    }
                }
            }
            if (targetNode == null) {
                targetNode = bestSoFar[BARITONE_COEFFICIENTS.length - 1];
            }
        }

        List<double[]> raw = new ArrayList<double[]>();
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
                for (int probe = raw.size() - 1; probe > idx + 1; probe--) {
                    double[] cand = raw.get(probe);
                    if (isFlightLineClear(world, from[0], from[1], from[2], cand[0], cand[1], cand[2])) {
                        furthest = probe;
                        break;
                    }
                }
                result.add(raw.get(furthest));
                idx = furthest;
            }
        }

        if (result.isEmpty()) {
            result.add(new double[]{gx, gy, gz});
        }
        if (expanded > 3 && ctrl != null) {
            // Throttle PATH spam like STUCK: same start/goal/outcome = once per 240 ticks.
            String pathLogKey = ctrl.id + "|path:" + startX + "," + startY + "," + startZ + "->" + goalX + "," + goalY + "," + goalZ + "|" + (reached != null ? "C" : "P");
            if (!pathLogKey.equals(ctrl.lastPathLogKey) || ctrl.tickClock - ctrl.lastPathLogTick >= 240) {
                this.logger.log("PATH", ctrl,
                    String.format("3D A* (%d,%d,%d)->(%d,%d,%d) %s",
                        startX, startY, startZ, goalX, goalY, goalZ, reached != null ? "COMPLETE" : "PARTIAL"),
                    "expanded=" + expanded + " waypoints=" + result.size());
                ctrl.lastPathLogKey = pathLogKey;
                ctrl.lastPathLogTick = ctrl.tickClock;
            }
        }
        if (ctrl != null) {
            ctrl.cachedPathSignature = pathSignature(sx, sy, sz, gx, gy, gz);
            ctrl.cachedPathCreatedTick = ctrl.tickClock;
        }
        return result;
    }

    // ==================== Delegated Breadcrumb Backtracking & Cave Escape ====================
    public int escapeCaveOrDigOut(DroneController ctrl, EntityPlayerMP owner) {
        return this.caveEscapeService.escapeCaveOrDigOut(ctrl, owner);
    }

    public void navigateToward(DroneController ctrl, double gx, double gy, double gz, float accel) {
        NavContext nav = NavContext.of(ctrl, gx, gy, gz, ctrl.navigationRoute);
        nav.setGoal(gx, gy, gz);
        Drone d = nav.drone();
        World world = nav.world();

        int curBx = MathHelper.func_76128_c(d.field_70165_t);
        int curBy = MathHelper.func_76128_c(d.field_70163_u);
        int curBz = MathHelper.func_76128_c(d.field_70161_v);
        int topCur = world.func_72976_f(curBx, curBz);
        boolean droneInOpenSky = world.func_72937_j(curBx, curBy, curBz) || d.field_70163_u >= topCur - 1;
        int topGoal = world.func_72976_f(MathHelper.func_76128_c(gx), MathHelper.func_76128_c(gz));
        boolean goalOnSurface = gy >= topGoal - 3;

        double effGx = gx;
        double effGy = gy;
        double effGz = gz;
        double horizDist = Math.hypot(gx - d.field_70165_t, gz - d.field_70161_v);

        if (!droneInOpenSky && goalOnSurface && (gy - d.field_70163_u > 6.0 || horizDist > 20.0)) {
            // Drone is underground trying to reach a surface goal: target surface exit above current position or last surface anchor first
            boolean anchorUsable = false;
            if (ctrl.lastSurfaceAnchor != null && Math.hypot(ctrl.lastSurfaceAnchor[0] - d.field_70165_t, ctrl.lastSurfaceAnchor[2] - d.field_70161_v) <= 24.0) {
                int ax = MathHelper.func_76128_c(ctrl.lastSurfaceAnchor[0]);
                int ay = ctrl.lastSurfaceAnchor[1];
                int az = MathHelper.func_76128_c(ctrl.lastSurfaceAnchor[2]);
                // A stale breadcrumb can sit inside rock now; never fly at a solid anchor.
                if (ItemClassifier.isPassableVoxel(world, ax, ay, az) && ItemClassifier.isPassableVoxel(world, ax, ay + 1, az)) {
                    effGx = ctrl.lastSurfaceAnchor[0] + 0.5;
                    effGy = ctrl.lastSurfaceAnchor[1] + 1.0;
                    effGz = ctrl.lastSurfaceAnchor[2] + 0.5;
                    anchorUsable = true;
                }
            }
            if (!anchorUsable) {
                int[] openAbove = findNearestPassableVoxel(world, curBx, Math.min(250, topCur + 2), curBz, 4);
                if (openAbove != null) {
                    effGx = openAbove[0] + 0.5;
                    effGy = openAbove[1] + 0.35;
                    effGz = openAbove[2] + 0.5;
                } else {
                    effGx = curBx + 0.5;
                    effGy = Math.min(250, topCur + 2.5);
                    effGz = curBz + 0.5;
                }
            }
        } else if (horizDist > (droneInOpenSky ? 18.0 : 32.0)) {
            double stepDist = droneInOpenSky ? 16.0 : 24.0;
            effGx = d.field_70165_t + (gx - d.field_70165_t) / horizDist * stepDist;
            effGz = d.field_70161_v + (gz - d.field_70161_v) / horizDist * stepDist;
            if (droneInOpenSky) {
                int topSub = world.func_72976_f(MathHelper.func_76128_c(effGx), MathHelper.func_76128_c(effGz));
                effGy = Math.max(gy, Math.max(topSub, topCur) + 4.5);
            } else {
                effGy = d.field_70163_u + (gy - d.field_70163_u) * (stepDist / horizDist);
            }
        }

        double distGoalMove = Double.isNaN(ctrl.pathGoalX) ? 999.0 : Math.sqrt(
            (effGx - ctrl.pathGoalX) * (effGx - ctrl.pathGoalX)
                + (effGy - ctrl.pathGoalY) * (effGy - ctrl.pathGoalY)
                + (effGz - ctrl.pathGoalZ) * (effGz - ctrl.pathGoalZ)
        );

        String route = nav.route();
        if ("tunnel".equals(route) && ctrl.mode == Mode.GOTO
            && !isFlightLineClear(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, effGx, effGy, effGz)) {
            int dug = this.caveEscapeService.digTowardTarget(
                ctrl, MathHelper.func_76128_c(effGx), MathHelper.func_76128_c(effGy), MathHelper.func_76128_c(effGz));
            if (dug > 0) return;
        }

        boolean needsRepath = nav.path().isEmpty()
            || (nav.pathIndex() >= nav.path().size() && ctrl.repathCooldown <= 0)
            || (distGoalMove > 0.85 && ctrl.repathCooldown <= 0)
            || (ctrl.stuckTicks >= 12 && ctrl.repathCooldown <= 0);

        if (needsRepath) {
            List<double[]> wps = findFlightPath(nav.withGoal(effGx, effGy, effGz));
            nav.setPath(wps, effGx, effGy, effGz);
            ctrl.repathCooldown = horizDist > 18.0 ? 8 : 12;

            // If trapped underground with a partial path, trigger automatic breadcrumb backtrack or Baritone dig-escape
            if (!"direct".equals(route) && !"tunnel".equals(route) && !droneInOpenSky && ctrl.lastPathPartial
                && (ctrl.stuckTicks >= 6 || (goalOnSurface && gy - d.field_70163_u > 6.0))) {
                escapeCaveOrDigOut(ctrl, null);
            }
        }

        while (nav.hasWaypoint()) {
            double[] wp = nav.currentWaypoint();
            double distWp = d.func_70011_f(wp[0], wp[1], wp[2]);
            boolean isLastWp = (nav.pathIndex() == nav.path().size() - 1);
            double distWpToGoal = Math.sqrt(
                (wp[0] - effGx) * (wp[0] - effGx)
                    + (wp[1] - effGy) * (wp[1] - effGy)
                    + (wp[2] - effGz) * (wp[2] - effGz)
            );
            if (distWp <= 0.75 && !isLastWp) {
                nav.advanceWaypoint();
            } else if (distWp <= 0.75 && isLastWp && (ctrl.lastPathPartial || distWpToGoal > 0.90) && ctrl.repathCooldown <= 0) {
                nav.advanceWaypoint();
                break;
            } else {
                break;
            }
        }

        double[] activeWp = nav.hasWaypoint()
            ? nav.currentWaypoint()
            : new double[]{effGx, effGy, effGz};
        int awx = MathHelper.func_76128_c(activeWp[0]);
        int away = MathHelper.func_76128_c(activeWp[1]);
        int awz = MathHelper.func_76128_c(activeWp[2]);
        // A waypoint that is inside collision territory must be recovered from, not
        // hovered at. The previous throttle compared one `lastSolidKey`: when two
        // different blocked goals alternated every tick (A/B/A/B) it saw "new key"
        // each time, so it logged every tick and NEVER escalated — 15.6k STUCK lines
        // and a permanently hovering drone. Count hits per blocked waypoint instead.
        if (!ItemClassifier.isPassableVoxel(world, awx, away, awz) || !ItemClassifier.isPassableVoxel(world, awx, away + 1, awz)) {
            FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 0.05f);
            d.field_70159_w *= 0.3;
            d.field_70181_x *= 0.3;
            d.field_70179_y *= 0.3;
            if (ctrl.stuckTicks < 6) {
                ctrl.stuckTicks = 6;
            }
            long solidKey = packBlockPos(awx, away, awz);
            Integer prevHits = ctrl.solidWaypointHits.get(Long.valueOf(solidKey));
            int hits = (prevHits == null ? 0 : prevHits.intValue()) + 1;
            ctrl.solidWaypointHits.put(Long.valueOf(solidKey), Integer.valueOf(hits));
            if (ctrl.solidWaypointHits.size() > 64) {
                ctrl.solidWaypointHits.clear();
                ctrl.solidWaypointHits.put(Long.valueOf(solidKey), Integer.valueOf(hits));
            }
            // Clear the fatally stuck path so the next navigateToward replans.
            nav.clearPath();
            ctrl.repathCooldown = 0;

            // One log per drone per 4s max, regardless of how many goals alternate.
            boolean logNow = ctrl.tickClock - ctrl.solidLogTick >= 80;
            if (logNow) {
                Block blockedAtWp = world.func_147439_a(awx, away, awz);
                this.logger.log("STUCK", ctrl, "Blocked waypoint; recovering",
                    String.format("wp=(%.2f,%.2f,%.2f) hits=%d block=%s mode=%s",
                        activeWp[0], activeWp[1], activeWp[2], hits,
                        ItemClassifier.getBlockDisplayName(blockedAtWp, world.func_72805_g(awx, away, awz)),
                        ctrl.mode.name()));
                ctrl.lastSolidKey = ctrl.id + "|solid:" + solidKey;
                ctrl.lastSolidLogTick = ctrl.tickClock;
                ctrl.solidLogTick = ctrl.tickClock;
            }
            // Escalate on the second hit of the SAME waypoint (or sooner if the
            // generic stuck timer is high). Dig the blocking voxel out instead of
            // hovering: this is the difference between "stuck" and "self-rescue".
            if (hits >= 2 || ctrl.stuckTicks >= 12) {
                int queued = this.caveEscapeService.digOutBlockedWaypoint(ctrl, awx, away, awz);
                if (queued > 0) {
                    ctrl.solidEscalations++;
                    this.logger.log("ESCAPE", ctrl, "Digging out blocked waypoint",
                        String.format("wp=(%.2f,%.2f,%.2f) queued=%d hits=%d escalation=%d",
                            activeWp[0], activeWp[1], activeWp[2], queued, hits, ctrl.solidEscalations));
                }
            }
        } else {
            // Making progress at THIS waypoint: forget only its own hit count.
            // Clearing the whole map here defeated escalation whenever a replan
            // produced a passable intermediate waypoint between two blocked
            // attempts at the same solid waypoint: the counter went 1 -> clear -> 1
            // forever, so the drone hovered instead of digging out.
            ctrl.solidWaypointHits.remove(Long.valueOf(packBlockPos(awx, away, awz)));
            setDroneFlightTarget(d, activeWp[0], activeWp[1], activeWp[2], accel);
        }
    }

    public static String describeNearestObstruction(World world, double x, double y, double z) {
        return DronePhysicsService.describeNearestObstruction(world, x, y, z);
    }

    public static void clearNearbyLeafObstruction(World world, DroneController ctrl) {
        DronePhysicsService.clearNearbyLeafObstruction(world, ctrl);
    }

    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride) {
        DronePhysicsService.runServerSidePhysicsStep(d, maxVelOverride);
    }

    public static void runServerSidePhysicsStep(Drone d, double maxVelOverride, DroneController ctrl) {
        DronePhysicsService.runServerSidePhysicsStep(d, maxVelOverride, ctrl);
    }

    public static boolean isPlayerLookingAtDrone(EntityPlayerMP player, Drone drone) {
        return DronePhysicsService.isPlayerLookingAtDrone(player, drone);
    }

    public static double[] computeStableFollowAnchor(World world, EntityPlayerMP target, Drone d) {
        return DronePhysicsService.computeStableFollowAnchor(world, target, d);
    }

    public static double[] findMiningStandoff(World world, Drone d, int bx, int by, int bz) {
        return DronePhysicsService.findMiningStandoff(world, d, bx, by, bz);
    }

    public static int[] findBlockingTunnelBlock(World world, Drone d, int targetX, int targetY, int targetZ) {
        return DronePhysicsService.findBlockingTunnelBlock(world, d, targetX, targetY, targetZ);
    }

    // ==================== Delegated Player Airlift ====================

    public void startCarryPlayerTask(DroneController ctrl, EntityPlayerMP player, double gx, double gy, double gz) {
        this.transportService.startCarryPlayerTask(ctrl, player, gx, gy, gz);
    }

    public void tickCarryBehavior(DroneController ctrl, EntityPlayerMP owner) {
        this.transportService.tickCarryBehavior(ctrl, owner);
    }
}
