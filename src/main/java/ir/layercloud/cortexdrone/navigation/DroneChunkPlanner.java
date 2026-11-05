package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;

/**
 * Pure chunk-plan geometry for {@link DroneChunkLoader}: which chunks are hot,
 * in priority order (highest first). It contains no Forge calls, so the plan
 * can be reasoned about and tested without a loaded world.
 */
final class DroneChunkPlanner {
    static final int DRONE_CHUNK_RADIUS = 1;       // 3x3 around the drone
    static final int MAX_TICKET_CHUNKS = 25;       // Forge default ticket depth
    static final int CORRIDOR_LOOKAHEAD_WAYPOINTS = 4;
    private static final int CORRIDOR_MAX_CHUNKS = 8;
    private static final int MINE_QUEUE_LOOKAHEAD = 2;
    private static final int GOAL_LOOKAHEAD_CHUNKS = 2;

    private DroneChunkPlanner() {
    }

    /**
     * Own chunk, own 3x3, the next corridor chunks nearest-first, then nearby
     * work targets. Far-away goals are deliberately omitted: navigation extends
     * the corridor as the drone advances, so the ticket never spends depth or
     * load budget on a chunk the drone will not reach soon.
     */
    static List<ChunkCoordIntPair> buildDronePlan(DroneController ctrl, int cx, int cz) {
        List<ChunkCoordIntPair> plan = new ArrayList<ChunkCoordIntPair>();
        Set<ChunkCoordIntPair> seen = new HashSet<ChunkCoordIntPair>();

        addChunk(plan, seen, cx, cz);
        for (int dx = -DRONE_CHUNK_RADIUS; dx <= DRONE_CHUNK_RADIUS; dx++) {
            for (int dz = -DRONE_CHUNK_RADIUS; dz <= DRONE_CHUNK_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                addChunk(plan, seen, cx + dx, cz + dz);
            }
        }

        double px = ctrl.drone.field_70165_t;
        double pz = ctrl.drone.field_70161_v;
        int added = 0;
        int limit = Math.min(ctrl.pathWaypoints.size(),
            ctrl.pathIndex + CORRIDOR_LOOKAHEAD_WAYPOINTS);
        for (int i = Math.max(0, ctrl.pathIndex); i < limit && added < CORRIDOR_MAX_CHUNKS; i++) {
            double[] wp = ctrl.pathWaypoints.get(i);
            if (wp == null) continue;
            added += addLineChunks(plan, seen, px, pz, wp[0], wp[2], CORRIDOR_MAX_CHUNKS - added);
            px = wp[0];
            pz = wp[2];
        }

        if (ctrl.currentMineTarget != null) {
            addNearChunk(plan, seen, ctrl.currentMineTarget[0] >> 4,
                ctrl.currentMineTarget[2] >> 4, cx, cz, GOAL_LOOKAHEAD_CHUNKS);
        }
        int queued = 0;
        for (int[] p : ctrl.mineQueue) {
            if (p == null) continue;
            addNearChunk(plan, seen, p[0] >> 4, p[2] >> 4, cx, cz, GOAL_LOOKAHEAD_CHUNKS);
            if (++queued >= MINE_QUEUE_LOOKAHEAD) break;
        }
        if (!Double.isNaN(ctrl.goalX) && !Double.isNaN(ctrl.goalZ)) {
            addNearChunk(plan, seen, MathHelper.func_76128_c(ctrl.goalX) >> 4,
                MathHelper.func_76128_c(ctrl.goalZ) >> 4, cx, cz, GOAL_LOOKAHEAD_CHUNKS);
        }

        if (plan.size() > MAX_TICKET_CHUNKS) {
            return new ArrayList<ChunkCoordIntPair>(plan.subList(0, MAX_TICKET_CHUNKS));
        }
        return plan;
    }

    static List<ChunkCoordIntPair> buildGrid(int cx, int cz, int radius) {
        List<ChunkCoordIntPair> grid = new ArrayList<ChunkCoordIntPair>();
        Set<ChunkCoordIntPair> seen = new HashSet<ChunkCoordIntPair>();
        addChunk(grid, seen, cx, cz);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                addChunk(grid, seen, cx + dx, cz + dz);
            }
        }
        return grid;
    }

    private static boolean addNearChunk(List<ChunkCoordIntPair> plan, Set<ChunkCoordIntPair> seen,
                                        int cx, int cz, int originCx, int originCz, int maxDistance) {
        if (Math.abs(cx - originCx) > maxDistance || Math.abs(cz - originCz) > maxDistance) {
            return false;
        }
        return addChunk(plan, seen, cx, cz);
    }

    private static boolean addChunk(List<ChunkCoordIntPair> plan, Set<ChunkCoordIntPair> seen,
                                    int cx, int cz) {
        ChunkCoordIntPair pair = new ChunkCoordIntPair(cx, cz);
        if (!seen.add(pair)) return false;
        plan.add(pair);
        return true;
    }

    private static int addLineChunks(List<ChunkCoordIntPair> plan, Set<ChunkCoordIntPair> seen,
                                     double x1, double z1, double x2, double z2, int max) {
        if (max <= 0) return 0;
        double dx = x2 - x1;
        double dz = z2 - z1;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / 4.0));
        int added = 0;
        for (int i = 0; i <= steps && added < max; i++) {
            double t = (double) i / (double) steps;
            int cx = MathHelper.func_76128_c(x1 + dx * t) >> 4;
            int cz = MathHelper.func_76128_c(z1 + dz * t) >> 4;
            if (addChunk(plan, seen, cx, cz)) added++;
        }
        return added;
    }
}
