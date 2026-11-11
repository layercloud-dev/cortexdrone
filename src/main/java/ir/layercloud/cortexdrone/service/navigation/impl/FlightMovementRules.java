package ir.layercloud.cortexdrone.service.navigation.impl;

/**
 * Movement-legality rules for the 3D flight A*.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. A drone occupies
 * two voxels (feet and head), so a step is only legal when both layers are free
 * and a diagonal does not clip a solid corner. These checks were inline in the
 * expansion loop; they are pure predicates over a voxel view, so they can be
 * tested directly and the search loop reads as search.</p>
 */
final class FlightMovementRules {
    /**
     * Voxel view the rules need: one passability query.
     */
    interface Voxels {
        boolean isPassable(int x, int y, int z);
    }

    private FlightMovementRules() {
    }

    /**
     * True when a step from {@code (cx, cy, cz)} by {@code (dx, dy, dz)} is legal.
     *
     * <p>Checks, in order: the destination feet cell, the destination head cell,
     * and — for a diagonal or corner step — every intermediate face and the
     * head-height faces. The head-layer checks exist because a feet-only test let
     * diagonal moves clip a solid block at head height and then velocity-block.</p>
     */
    static boolean isStepLegal(Voxels voxels, int cx, int cy, int cz,
                               int dx, int dy, int dz) {
        if (voxels == null) return false;
        int nx = cx + dx;
        int ny = cy + dy;
        int nz = cz + dz;
        if (!voxels.isPassable(nx, ny, nz)) return false;
        // The drone's body spans into ny+1; a solid block there clips it.
        if (!voxels.isPassable(nx, ny + 1, nz)) return false;

        int manhattan = FlightStepRules.manhattan(dx, dy, dz);
        if (manhattan < 2) return true;

        if (dx != 0 && !voxels.isPassable(cx + dx, cy, cz)) return false;
        if (dy != 0 && !voxels.isPassable(cx, cy + dy, cz)) return false;
        if (dz != 0 && !voxels.isPassable(cx, cy, cz + dz)) return false;
        if (dx != 0 && !voxels.isPassable(cx + dx, cy + 1, cz)) return false;
        if (dy != 0 && !voxels.isPassable(cx, cy + dy + 1, cz)) return false;
        if (dz != 0 && !voxels.isPassable(cx, cy + 1, cz + dz)) return false;
        if (manhattan == 3) {
            if (!voxels.isPassable(cx + dx, cy + dy, cz)) return false;
            if (!voxels.isPassable(cx + dx, cy, cz + dz)) return false;
            if (!voxels.isPassable(cx, cy + dy, cz + dz)) return false;
        }
        return true;
    }

    /**
     * True when the cell is a legal drone position at all (feet + head free).
     */
    static boolean isPositionLegal(Voxels voxels, int x, int y, int z) {
        return voxels != null && voxels.isPassable(x, y, z)
                && voxels.isPassable(x, y + 1, z);
    }
}
