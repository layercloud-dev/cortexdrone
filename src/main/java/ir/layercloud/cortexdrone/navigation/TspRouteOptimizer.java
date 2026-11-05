package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.model.BuildTask;
import java.util.ArrayList;
import java.util.List;

/**
 * Travelling Salesman Problem (TSP) 3D route optimizer using Nearest-Neighbor
 * construction followed by 2-Opt local search edge-uncrossing.
 * Minimizes total drone flight distance across mining, farming, and building queues.
 */
public final class TspRouteOptimizer {
    private static final int MAX_2OPT_POINTS = 32;
    private static final int MAX_2OPT_PASSES = 1;

    private TspRouteOptimizer() {
    }

    private static double dist3D(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distFromPoint(double sx, double sy, double sz, int[] b) {
        double dx = (b[0] + 0.5) - sx;
        double dy = (b[1] + 0.5) - sy;
        double dz = (b[2] + 0.5) - sz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Optimizes a list of 3D block coordinates `[x, y, z]` into a continuous, non-crossing tour
     * starting at `(startX, startY, startZ)` using Nearest-Neighbor + 2-Opt local search.
     */
    public static List<int[]> optimizeBlockTour(List<int[]> points, double startX, double startY, double startZ) {
        if (points == null || points.size() <= 2) {
            return points != null ? new ArrayList<int[]>(points) : new ArrayList<int[]>();
        }

        int n = points.size();
        boolean[] used = new boolean[n];
        List<int[]> tour = new ArrayList<int[]>(n);

        // 1) Nearest-Neighbor Tour Construction
        double curX = startX, curY = startY, curZ = startZ;
        for (int step = 0; step < n; step++) {
            int bestIdx = -1;
            double bestDistSq = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                int[] p = points.get(i);
                double dx = (p[0] + 0.5) - curX;
                double dy = (p[1] + 0.5) - curY;
                double dz = (p[2] + 0.5) - curZ;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;
            used[bestIdx] = true;
            int[] chosen = points.get(bestIdx);
            tour.add(chosen);
            curX = chosen[0] + 0.5;
            curY = chosen[1] + 0.5;
            curZ = chosen[2] + 0.5;
        }

        // 2) 2-Opt Local Search Edge-Uncrossing (on the first MAX_2OPT_POINTS points)
        int optLen = Math.min(tour.size(), MAX_2OPT_POINTS);
        for (int pass = 0; pass < MAX_2OPT_PASSES; pass++) {
            boolean improved = false;
            for (int i = 0; i < optLen - 2; i++) {
                int[] a = tour.get(i);
                int[] b = tour.get(i + 1);
                for (int j = i + 2; j < optLen; j++) {
                    int[] c = tour.get(j);
                    int[] d = (j + 1 < optLen) ? tour.get(j + 1) : null;
                    double currentCost = dist3D(a, b) + (d != null ? dist3D(c, d) : 0.0);
                    double swappedCost = dist3D(a, c) + (d != null ? dist3D(b, d) : 0.0);
                    if (swappedCost + 0.05 < currentCost) {
                        reverseSubList(tour, i + 1, j);
                        b = tour.get(i + 1);
                        improved = true;
                    }
                }
            }
            if (!improved) break;
        }
        return tour;
    }

    private static <T> void reverseSubList(List<T> list, int left, int right) {
        while (left < right) {
            T tmp = list.get(left);
            list.set(left, list.get(right));
            list.set(right, tmp);
            left++;
            right--;
        }
    }

    /**
     * Optimizes a list of BuildTasks layer-by-layer (bottom-up Y order for structural support,
     * with Nearest-Neighbor + 2-Opt continuous tour optimization within each Y layer).
     */
    public static List<BuildTask> optimizeBuildTasksByLayer(List<BuildTask> tasks, double startX, double startY, double startZ) {
        if (tasks == null || tasks.size() <= 2) {
            return tasks != null ? new ArrayList<BuildTask>(tasks) : new ArrayList<BuildTask>();
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BuildTask t : tasks) {
            if (t.y < minY) minY = t.y;
            if (t.y > maxY) maxY = t.y;
        }

        List<BuildTask> optimized = new ArrayList<BuildTask>(tasks.size());
        double curX = startX, curY = startY, curZ = startZ;

        for (int y = minY; y <= maxY; y++) {
            List<BuildTask> layer = new ArrayList<BuildTask>();
            for (BuildTask t : tasks) {
                if (t.y == y) layer.add(t);
            }
            if (layer.isEmpty()) continue;
            if (layer.size() <= 2) {
                optimized.addAll(layer);
                BuildTask last = layer.get(layer.size() - 1);
                curX = last.x + 0.5;
                curY = last.y + 0.5;
                curZ = last.z + 0.5;
                continue;
            }

            // Nearest-Neighbor construction within layer y
            int n = layer.size();
            boolean[] used = new boolean[n];
            List<BuildTask> layerTour = new ArrayList<BuildTask>(n);
            for (int step = 0; step < n; step++) {
                int bestIdx = -1;
                double bestDistSq = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (used[i]) continue;
                    BuildTask bt = layer.get(i);
                    double dx = (bt.x + 0.5) - curX;
                    double dz = (bt.z + 0.5) - curZ;
                    double dSq = dx * dx + dz * dz;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestIdx = i;
                    }
                }
                if (bestIdx < 0) break;
                used[bestIdx] = true;
                BuildTask chosen = layer.get(bestIdx);
                layerTour.add(chosen);
                curX = chosen.x + 0.5;
                curY = chosen.y + 0.5;
                curZ = chosen.z + 0.5;
            }

            // 2-Opt pass within layer y
            int optLen = Math.min(layerTour.size(), MAX_2OPT_POINTS);
            for (int pass = 0; pass < MAX_2OPT_PASSES; pass++) {
                boolean improved = false;
                for (int i = 0; i < optLen - 2; i++) {
                    BuildTask a = layerTour.get(i);
                    BuildTask b = layerTour.get(i + 1);
                    for (int j = i + 2; j < optLen; j++) {
                        BuildTask c = layerTour.get(j);
                        BuildTask d = (j + 1 < optLen) ? layerTour.get(j + 1) : null;
                        double curCost = Math.hypot(a.x - b.x, a.z - b.z) + (d != null ? Math.hypot(c.x - d.x, c.z - d.z) : 0.0);
                        double newCost = Math.hypot(a.x - c.x, a.z - c.z) + (d != null ? Math.hypot(b.x - d.x, b.z - d.z) : 0.0);
                        if (newCost + 0.05 < curCost) {
                            reverseSubList(layerTour, i + 1, j);
                            b = layerTour.get(i + 1);
                            improved = true;
                        }
                    }
                }
                if (!improved) break;
            }

            optimized.addAll(layerTour);
            BuildTask last = layerTour.get(layerTour.size() - 1);
            curX = last.x + 0.5;
            curY = last.y + 0.5;
            curZ = last.z + 0.5;
        }

        return optimized;
    }
}
