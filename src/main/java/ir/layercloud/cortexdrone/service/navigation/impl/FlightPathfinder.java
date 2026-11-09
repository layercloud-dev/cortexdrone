package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.raytrace.ChunkCacheContext;
import ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D;
import li.cil.oc.common.entity.Drone;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.*;

public class FlightPathfinder {
    /** Extra A* cost of a step that enters lava. */
    public static final double LAVA_STEP_COST = 8.0;
    /** Base A* cost of a normal diagonal/straight step. */
    public static final double BASE_STEP_COST = 2.0;

    private final DroneLogger logger;

    public FlightPathfinder(DroneLogger logger) {
        this.logger = logger;
    }

    public static long packBlockPos(int x, int y, int z) {
        return ir.layercloud.cortexdrone.service.world.BlockPosKey.pack(x, y, z);
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
        if (ItemClassifier.isPassableVoxel(world, cx, cy, cz)
                && !ItemClassifier.isDangerousLiquid(world.getBlock(cx, cy, cz))) {
            return new int[]{cx, cy, cz};
        }
        for (int r = 1; r <= maxRadius; r++) {
            int[] best = null;
            int bestDistSq = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = cx + dx;
                        int y = cy + dy;
                        int z = cz + dz;
                        if (ItemClassifier.isPassableVoxel(world, x, y, z)
                                && !ItemClassifier.isDangerousLiquid(world.getBlock(x, y, z))) {
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
                gx = goalX + DroneGeometry.CELL_CENTER;
                gy = goalY + ir.layercloud.cortexdrone.service.navigation.DroneGeometry.HOVER_OFFSET;
                gz = goalZ + DroneGeometry.CELL_CENTER;
            }
        }

        FlightAStar.Search search = FlightAStar.run(bsi, startX, startY, startZ,
                goalX, goalY, goalZ);
        int expanded = search.expanded;
        if (nav != null) {
            nav.lastPathNodesExpanded = expanded;
            nav.lastPathPartial = search.reached == null;
        }

        AStarNode targetNode = search.partial;
        if (targetNode == null) {
            // Returning the start node here used to produce a one-waypoint path
            // that immediately blocked, burning the recovery budget on detours
            // before digging could start.
            if (nav != null) nav.lastPathPartial = true;
            return result;
        }

        List<double[]> raw = new ArrayList<>();
        AStarNode ptr = targetNode;
        while (ptr != null) {
            raw.add(new double[]{ptr.x + DroneGeometry.CELL_CENTER,
                    ptr.y + DroneGeometry.HOVER_OFFSET,
                    ptr.z + DroneGeometry.CELL_CENTER});
            ptr = ptr.parent;
        }
        Collections.reverse(raw);
        if (search.reached != null) {
            raw.add(new double[]{gx, gy, gz});
        }

        result.addAll(FlightPathSmoother.smooth(raw, sx, sy, sz, FlightPathSmoother.LOOKAHEAD,
                (x1, y1, z1, x2, y2, z2) -> VoxelRaycaster3D.isFatLineClear(bsi, x1, y1, z1, x2, y2, z2)));

        if (result.isEmpty()) {
            // A partial path that never left the start cell is not progress.
            if (nav != null) nav.lastPathPartial = true;
            return result;
        }
        if (expanded > 3 && ctrl != null && nav != null) {
            // Throttle PATH spam like STUCK: same start/goal/outcome = once per 240 ticks.
            String pathLogKey = ctrl.id + "|path:" + startX + "," + startY + "," + startZ + "->" + goalX + "," + goalY + "," + goalZ + "|" + (search.reached != null ? "C" : "P");
            if (!pathLogKey.equals(nav.lastPathLogKey) || nav.ticks - nav.lastPathLogTick >= 240) {
                this.logger.log("PATH", ctrl,
                        String.format("3D A* (%d,%d,%d)->(%d,%d,%d) %s",
                                startX, startY, startZ, goalX, goalY, goalZ, search.reached != null ? "COMPLETE" : "PARTIAL"),
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
