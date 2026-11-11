package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.ChunkCacheContext;
import net.minecraft.block.Block;

import java.util.*;

/**
 * The 3D flight A* search.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. This class owns the
 * open/closed sets, the expansion loop and the Theta* shortcut; the caller owns
 * goal repair, caching, smoothing and logging.</p>
 *
 * <p>Two policies are deliberate:</p>
 * <ul>
 *   <li><b>Bounded search</b> — a node cap and a wall-clock budget, because this
 *       runs on the server tick.</li>
 *   <li><b>Partial results</b> — when the goal is unreachable the search returns
 *       the best node seen under each Baritone backoff coefficient, so the drone
 *       still makes progress instead of failing outright.</li>
 * </ul>
 */
final class FlightAStar {
    /**
     * Coefficients for the multi-heuristic partial-path backoff (Baritone).
     */
    private static final double[] BACKOFF_COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};
    /**
     * A node only replaces a best partial when it improves by this much.
     */
    private static final double MIN_IMPROVEMENT = 0.01D;
    /**
     * Hard cap on expanded nodes; the search runs on the server tick.
     */
    static final int MAX_EXPANSIONS = 1400;
    /**
     * Wall-clock budget for one search.
     */
    static final long SEARCH_BUDGET_NANOS = 4_000_000L;
    /**
     * Theta* shortcuts only within this squared distance.
     */
    private static final double MAX_THETA_DIST_SQ = 64.0D;
    /**
     * Multiplier applied to the heuristic for the f-score.
     */
    private static final double HEURISTIC_WEIGHT = 1.15D;
    /**
     * Preferred minimum distance for a partial path, in blocks.
     */
    static final double MIN_PARTIAL_DIST = 5.0D;
    /**
     * Shortest segment still worth returning, in blocks.
     */
    static final double MIN_PARTIAL_STEP = 2.0D;

    /**
     * Outcome of one search.
     */
    static final class Search {
        /**
         * The goal node when reached, otherwise {@code null}.
         */
        AStarNode reached;
        /**
         * Best partial node, used when the goal was unreachable.
         */
        AStarNode partial;
        /**
         * Nodes expanded.
         */
        int expanded;
    }

    private FlightAStar() {
    }

    /**
     * Runs the search from {@code (sx,sy,sz)} to {@code (gx,gy,gz)}.
     *
     * @param voxels a chunk-backed voxel view positioned near the start
     */
    static Search run(ChunkCacheContext voxels, int sx, int sy, int sz,
                      int gx, int gy, int gz) {
        Search result = new Search();
        PriorityQueue<AStarNode> open = new PriorityQueue<>();
        Map<Long, Double> bestG = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        double h0 = heuristic(sx, sy, sz, gx, gy, gz);
        AStarNode startNode = new AStarNode(sx, sy, sz, 0.0D, h0, h0, null);
        open.add(startNode);
        bestG.put(FlightPathfinder.packBlockPos(sx, sy, sz), 0.0D);

        AStarNode[] bestSoFar = new AStarNode[BACKOFF_COEFFICIENTS.length];
        double[] bestHeuristicSoFar = new double[BACKOFF_COEFFICIENTS.length];
        for (int i = 0; i < BACKOFF_COEFFICIENTS.length; i++) {
            bestSoFar[i] = startNode;
            bestHeuristicSoFar[i] = h0;
        }

        long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;
        while (!open.isEmpty() && result.expanded < MAX_EXPANSIONS
                && System.nanoTime() < deadline) {
            AStarNode current = open.poll();
            if (current == null) break;
            if (!closed.add(FlightPathfinder.packBlockPos(current.x, current.y, current.z))) continue;
            result.expanded++;

            if (current.x == gx && current.y == gy && current.z == gz) {
                result.reached = current;
                break;
            }
            expand(voxels, current, gx, gy, gz, open, bestG, closed,
                    bestSoFar, bestHeuristicSoFar);
        }

        result.partial = result.reached != null ? result.reached
                : bestPartialNode(bestSoFar, sx, sy, sz);
        return result;
    }

    /**
     * Expands every legal neighbour of {@code current}.
     */
    private static void expand(ChunkCacheContext voxels, AStarNode current,
                               int gx, int gy, int gz,
                               PriorityQueue<AStarNode> open, Map<Long, Double> bestG,
                               Set<Long> closed, AStarNode[] bestSoFar,
                               double[] bestHeuristicSoFar) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = current.x + dx;
                    int ny = current.y + dy;
                    int nz = current.z + dz;
                    long key = FlightPathfinder.packBlockPos(nx, ny, nz);
                    if (closed.contains(key)) continue;
                    if (!FlightMovementRules.isStepLegal(voxels::isPassable,
                            current.x, current.y, current.z, dx, dy, dz)) {
                        continue;
                    }

                    Block destination = voxels.getBlock(nx, ny, nz);
                    boolean inLiquid = ItemClassifier.isLiquidBlock(destination);
                    boolean hasGround = !voxels.isPassable(nx, ny - 1, nz);
                    double stepCost = FlightStepRules.withPenalties(
                            FlightStepRules.baseCost(dx, dy, dz),
                            inLiquid, inLiquid && ItemClassifier.isDangerousLiquid(destination),
                            hasGround);

                    AStarNode parent = current;
                    double g = current.g + stepCost;
                    if (current.parent != null && !inLiquid) {
                        double shortcut = shortcutCost(voxels, current, nx, ny, nz, g);
                        if (shortcut >= 0.0D) {
                            parent = current.parent;
                            g = shortcut;
                        }
                    }

                    Double previous = bestG.get(key);
                    if (previous != null && g >= previous) continue;
                    bestG.put(key, g);
                    double h = heuristic(nx, ny, nz, gx, gy, gz);
                    AStarNode child = new AStarNode(nx, ny, nz, g, h,
                            g + h * HEURISTIC_WEIGHT, parent);
                    open.add(child);
                    rememberPartial(bestSoFar, bestHeuristicSoFar, child, g, h);
                }
            }
        }
    }

    /**
     * Theta* shortcut: returns the g-score of reaching the neighbour directly
     * from {@code current.parent}, or {@code -1} when no shortcut applies.
     */
    private static double shortcutCost(ChunkCacheContext voxels, AStarNode current,
                                       int nx, int ny, int nz, double currentG) {
        AStarNode grandparent = current.parent;
        if (squaredDistance(grandparent, nx, ny, nz) > MAX_THETA_DIST_SQ) return -1.0D;
        boolean clear = ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D
                .isFatLineClear(voxels,
                        grandparent.x + DroneGeometry.CELL_CENTER,
                        grandparent.y + DroneGeometry.HOVER_OFFSET,
                        grandparent.z + DroneGeometry.CELL_CENTER,
                        nx + DroneGeometry.CELL_CENTER,
                        ny + DroneGeometry.HOVER_OFFSET,
                        nz + DroneGeometry.CELL_CENTER);
        if (!clear) return -1.0D;
        double distance = Math.sqrt(
                (double) (nx - grandparent.x) * (nx - grandparent.x)
                        + (double) (ny - grandparent.y) * (ny - grandparent.y)
                        + (double) (nz - grandparent.z) * (nz - grandparent.z));
        if (!voxels.isPassable(nx, ny - 1, nz)) distance += FlightStepRules.NO_GROUND_PENALTY;
        double candidate = grandparent.g + distance;
        return candidate <= currentG ? candidate : -1.0D;
    }

    /**
     * Records a node as the best partial under each backoff coefficient.
     */
    private static void rememberPartial(AStarNode[] bestSoFar, double[] bestHeuristicSoFar,
                                        AStarNode child, double g, double h) {
        for (int i = 0; i < BACKOFF_COEFFICIENTS.length; i++) {
            double metric = h + g / BACKOFF_COEFFICIENTS[i];
            if (bestHeuristicSoFar[i] - metric > MIN_IMPROVEMENT) {
                bestHeuristicSoFar[i] = metric;
                bestSoFar[i] = child;
            }
        }
    }

    /**
     * Baritone's {@code bestSoFar} selection: the first coefficient whose
     * furthest node is at least the minimum partial distance from the start.
     * Never returns the start node.
     */
    private static AStarNode bestPartialNode(AStarNode[] bestSoFar, int sx, int sy, int sz) {
        AStarNode useful = firstAtLeast(bestSoFar, sx, sy, sz, MIN_PARTIAL_DIST * MIN_PARTIAL_DIST);
        if (useful != null) return useful;
        // A tight pocket may offer nothing far away. A short but real segment is
        // still progress: it is re-planned from its end, so the drone walks out
        // of the pocket one step at a time.
        return firstAtLeast(bestSoFar, sx, sy, sz, MIN_PARTIAL_STEP * MIN_PARTIAL_STEP);
    }

    private static AStarNode firstAtLeast(AStarNode[] bestSoFar, int sx, int sy, int sz,
                                          double minSq) {
        for (AStarNode candidate : bestSoFar) {
            if (candidate == null) continue;
            if (squaredDistance(candidate, sx, sy, sz) >= minSq) return candidate;
        }
        return null;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = gx - x;
        double dy = gy - y;
        double dz = gz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double squaredDistance(AStarNode node, int x, int y, int z) {
        double dx = node.x - x;
        double dy = node.y - y;
        double dz = node.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
