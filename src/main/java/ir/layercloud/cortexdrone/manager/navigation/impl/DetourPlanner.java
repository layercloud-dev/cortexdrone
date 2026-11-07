package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

import java.util.Set;

/**
 * Candidate detour goals for navigation recovery: above the goal first (most
 * maps are open above), then the four lateral faces, then local escapes around
 * the drone. Points that repeat or land inside solid rock are skipped.
 */
final class DetourPlanner {
    private DetourPlanner() {
    }

    /**
     * @param attempt in/out cursor; starts at the session detour index and is
     *                advanced for every candidate considered
     * @return a safe detour point, or {@code null} when candidates are exhausted
     */
    static double[] next(int[] attempt, Set<Long> rejected,
                         NavigationState state, Drone drone, World world) {
        double[][] candidates = new double[][]{
                {state.goalX, state.goalY + 4.0D, state.goalZ},
                {state.goalX, state.goalY + 8.0D, state.goalZ},
                {state.goalX, state.goalY + 12.0D, state.goalZ},
                {state.goalX + 6.0D, state.goalY, state.goalZ},
                {state.goalX - 6.0D, state.goalY, state.goalZ},
                {state.goalX, state.goalY, state.goalZ + 6.0D},
                {state.goalX, state.goalY, state.goalZ - 6.0D},
                {state.goalX + 6.0D, state.goalY + 3.0D, state.goalZ + 6.0D},
                {state.goalX - 6.0D, state.goalY + 3.0D, state.goalZ - 6.0D},
                {state.goalX + 6.0D, state.goalY + 6.0D, state.goalZ - 6.0D},
                {state.goalX - 6.0D, state.goalY + 6.0D, state.goalZ + 6.0D},
                {drone.posX, Math.min(253.0D, drone.posY + 8.0D), drone.posZ},
                {drone.posX, Math.max(3.0D, drone.posY - 6.0D), drone.posZ},
                {drone.posX + 6.0D, drone.posY, drone.posZ},
                {drone.posX - 6.0D, drone.posY, drone.posZ},
                {drone.posX, drone.posY, drone.posZ + 6.0D},
                {drone.posX, drone.posY, drone.posZ - 6.0D},
                {drone.posX + 12.0D, drone.posY + 2.0D, drone.posZ},
                {drone.posX - 12.0D, drone.posY + 2.0D, drone.posZ},
                {drone.posX, drone.posY + 2.0D, drone.posZ + 12.0D},
                {drone.posX, drone.posY + 2.0D, drone.posZ - 12.0D}
        };
        while (attempt[0] < candidates.length) {
            double[] candidate = candidates[attempt[0]++];
            int bx = (int) Math.floor(candidate[0]);
            int by = (int) Math.floor(candidate[1]);
            int bz = (int) Math.floor(candidate[2]);
            if (by <= 2 || by >= 254) continue;
            if (!world.blockExists(bx, by, bz)) continue;
            if (!ItemClassifier.isPassableVoxel(world, bx, by, bz)) continue;
            if (!ItemClassifier.isPassableVoxel(world, bx, by + 1, bz)) continue;
            long key = pack(bx, by, bz);
            if (rejected.contains(key)) continue;
            rejected.add(key);
            return new double[]{bx + 0.5D, by + 0.35D, bz + 0.5D};
        }
        return null;
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }
}
