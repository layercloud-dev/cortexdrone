package ir.layercloud.cortexdrone.manager.chunk.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure chunk-plan geometry for {@link ChunkLoadingManager}: which chunks are
 * hot, highest priority first. It contains no Forge or world calls, so the plan
 * can be tested without a live server.
 */
final class NavigationChunkPlanner {
    static final int DRONE_CHUNK_RADIUS = 1;        // 3x3 around the drone
    static final int DRONE_KEEP_RADIUS = 2;         // never release inside this
    static final int MAX_TICKET_CHUNKS = 25;        // Forge default ticket depth
    static final int CORRIDOR_LOOKAHEAD_WAYPOINTS = 4;
    private static final int CORRIDOR_MAX_CHUNKS = 8;
    private static final int GOAL_LOOKAHEAD_CHUNKS = 2;
    private static final int GOAL_AHEAD_CHUNKS = 4;

    private NavigationChunkPlanner() {
    }

    /**
     * Drone chunk, its 3x3, the next corridor chunks nearest-first, then a
     * short line toward the goal. A far goal is deliberately not claimed: the
     * rolling corridor extends as the drone advances, so the ticket never
     * spends its depth on a chunk the drone will not reach soon.
     */
    static List<ChunkCoordIntPair> buildDronePlan(DroneController ctrl,
                                                  NavigationState nav,
                                                  int chunkX, int chunkZ) {
        List<ChunkCoordIntPair> plan = new ArrayList<>();
        Set<ChunkCoordIntPair> seen = new HashSet<>();

        addChunk(plan, seen, chunkX, chunkZ);
        for (int dx = -DRONE_CHUNK_RADIUS; dx <= DRONE_CHUNK_RADIUS; dx++) {
            for (int dz = -DRONE_CHUNK_RADIUS; dz <= DRONE_CHUNK_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                addChunk(plan, seen, chunkX + dx, chunkZ + dz);
            }
        }

        if (ctrl == null || ctrl.drone == null || nav == null) {
            return cap(plan);
        }

        double px = ctrl.drone.posX;
        double pz = ctrl.drone.posZ;
        int added = 0;
        int end = Math.min(nav.pathWaypoints.size(),
                Math.max(0, nav.pathIndex) + CORRIDOR_LOOKAHEAD_WAYPOINTS);
        for (int i = Math.max(0, nav.pathIndex); i < end && added < CORRIDOR_MAX_CHUNKS; i++) {
            double[] waypoint = nav.pathWaypoints.get(i);
            if (waypoint == null || waypoint.length < 3) continue;
            added += addLineChunks(plan, seen, px, pz, waypoint[0], waypoint[2],
                    CORRIDOR_MAX_CHUNKS - added);
            px = waypoint[0];
            pz = waypoint[2];
        }

        if (!Double.isNaN(nav.goalX) && !Double.isNaN(nav.goalZ)) {
            int ahead = Math.min(GOAL_AHEAD_CHUNKS, CORRIDOR_MAX_CHUNKS - added);
            if (ahead > 0) addLineChunks(plan, seen, px, pz, nav.goalX, nav.goalZ, ahead);
            addNearChunk(plan, seen,
                    MathHelper.floor_double(nav.goalX) >> 4,
                    MathHelper.floor_double(nav.goalZ) >> 4,
                    chunkX, chunkZ, GOAL_LOOKAHEAD_CHUNKS);
        }
        return cap(plan);
    }

    private static List<ChunkCoordIntPair> cap(List<ChunkCoordIntPair> plan) {
        if (plan.size() <= MAX_TICKET_CHUNKS) return plan;
        return new ArrayList<>(plan.subList(0, MAX_TICKET_CHUNKS));
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
            int cx = MathHelper.floor_double(x1 + dx * t) >> 4;
            int cz = MathHelper.floor_double(z1 + dz * t) >> 4;
            if (addChunk(plan, seen, cx, cz)) added++;
        }
        return added;
    }
}
