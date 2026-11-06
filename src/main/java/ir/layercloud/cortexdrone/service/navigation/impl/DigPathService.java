package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.ChunkCacheContext;
import net.minecraft.block.Block;
import net.minecraft.world.World;

import java.util.*;

/**
 * Read-only weighted A* dig-path planner.
 *
 * <p>It returns an ordered list of steps encoded as {@code {x, y, z, dig}} and
 * never touches the drone, its mode or its queues. The navigation manager
 * decides whether to execute the route.</p>
 */
public final class DigPathService {
    private static final int[][] DIRECTIONS = new int[][]{
            {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
    };

    public List<int[]> findPath(World world, int sx, int sy, int sz,
                                int gx, int gy, int gz, boolean toOpenSky) {
        ChunkCacheContext bsi = new ChunkCacheContext(world, sx, sz, 2);
        PriorityQueue<FlightPathfinder.AStarNode> open =
                new PriorityQueue<FlightPathfinder.AStarNode>();
        Map<Long, Double> bestG = new HashMap<Long, Double>();
        Set<Long> closed = new HashSet<Long>();

        double h0 = toOpenSky
                ? Math.max(0, 75 - sy)
                : Math.sqrt((gx - sx) * (gx - sx) + (gy - sy) * (gy - sy) + (gz - sz) * (gz - sz));
        FlightPathfinder.AStarNode start =
                new FlightPathfinder.AStarNode(sx, sy, sz, 0.0, h0, h0, false, null);
        open.add(start);
        bestG.put(FlightPathfinder.packBlockPos(sx, sy, sz), 0.0);

        FlightPathfinder.AStarNode reached = null;
        FlightPathfinder.AStarNode bestPartial = start;
        double bestMetric = h0;
        int expanded = 0;

        while (!open.isEmpty() && expanded < 450) {
            FlightPathfinder.AStarNode current = open.poll();
            long currentKey = FlightPathfinder.packBlockPos(current.x, current.y, current.z);
            if (!closed.add(currentKey)) continue;
            expanded++;

            if (toOpenSky) {
                if (bsi.isPassable(current.x, current.y, current.z)
                        && world.canBlockSeeTheSky(current.x, current.y, current.z)) {
                    reached = current;
                    break;
                }
            } else if (Math.abs(current.x - gx) + Math.abs(current.y - gy)
                    + Math.abs(current.z - gz) <= 1) {
                reached = current;
                break;
            }

            for (int[] direction : DIRECTIONS) {
                int nx = current.x + direction[0];
                int ny = current.y + direction[1];
                int nz = current.z + direction[2];
                if (ny <= 2 || ny >= 253) continue;
                long nextKey = FlightPathfinder.packBlockPos(nx, ny, nz);
                if (closed.contains(nextKey)) continue;

                boolean passable = bsi.isPassable(nx, ny, nz);
                double stepCost;
                boolean dig = false;
                if (passable) {
                    Block block = bsi.getBlock(nx, ny, nz);
                    if (ItemClassifier.isLiquidBlock(block)) continue;
                    stepCost = 1.0;
                } else {
                    if (!ItemClassifier.isMineableBlock(world, nx, ny, nz)) continue;
                    if (hasAdjacentLiquid(bsi, nx, ny, nz)) continue;
                    Block block = bsi.getBlock(nx, ny, nz);
                    float hardness = block != null ? block.getBlockHardness(world, nx, ny, nz) : 1.5f;
                    if (hardness < 0.0f || hardness > 30.0f) continue;
                    stepCost = 4.0 + hardness * 2.5;
                    dig = true;
                }

                double nextG = current.g + stepCost;
                Double previousG = bestG.get(nextKey);
                if (previousG != null && nextG >= previousG) continue;

                bestG.put(nextKey, nextG);
                double nextH = toOpenSky
                        ? Math.max(0.0, (world.getHeightValue(nx, nz) - ny) * 1.2)
                        : Math.sqrt((gx - nx) * (gx - nx) + (gy - ny) * (gy - ny)
                        + (gz - nz) * (gz - nz));
                FlightPathfinder.AStarNode child = new FlightPathfinder.AStarNode(
                        nx, ny, nz, nextG, nextH, nextG + nextH * 1.2, dig, current);
                open.add(child);
                double metric = nextH + nextG / 3.0;
                if (metric < bestMetric && (nx != sx || ny != sy || nz != sz)) {
                    bestMetric = metric;
                    bestPartial = child;
                }
            }
        }

        FlightPathfinder.AStarNode end = reached != null ? reached : bestPartial;
        List<int[]> path = new ArrayList<int[]>();
        FlightPathfinder.AStarNode pointer = end;
        while (pointer != null && pointer.parent != null) {
            path.add(new int[]{pointer.x, pointer.y, pointer.z, pointer.requiresDig ? 1 : 0});
            pointer = pointer.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean hasAdjacentLiquid(ChunkCacheContext cache, int x, int y, int z) {
        for (int[] direction : DIRECTIONS) {
            if (ItemClassifier.isLiquidBlock(cache.getBlock(
                    x + direction[0], y + direction[1], z + direction[2]))) {
                return true;
            }
        }
        return false;
    }
}
