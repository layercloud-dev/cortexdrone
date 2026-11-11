package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class BlockSightTest {
    /**
     * Solid 3x3x3 cube of stone from (0,0,0) to (2,2,2), everything else air.
     */
    private static final BlockSight.VoxelPassability CUBE = (x, y, z) ->
            x < 0 || x > 2 || y < 0 || y > 2 || z < 0 || z > 2;

    private static Set<String> voxels(int[]... positions) {
        Set<String> set = new HashSet<>();
        for (int[] p : positions) set.add(p[0] + "," + p[1] + "," + p[2]);
        return set;
    }

    @Test
    public void clearLineToTargetReturnsNull() {
        assertNull(BlockSight.firstBlockingVoxel(CUBE,
                5.5, 1.5, 1.5, 2.5, 1.5, 1.5, 2, 1, 1));
    }

    @Test
    public void wallBetweenDroneAndTargetIsReported() {
        // The front face (x=2) is solid, so a ray from outside stops on it
        // before reaching the target (0,1,1) deep inside the cube.
        int[] hit = BlockSight.firstBlockingVoxel(CUBE,
                5.5, 1.5, 1.5, 0.5, 1.5, 1.5, 0, 1, 1);
        assertNotNull(hit);
        assertEquals(2, hit[0]);
    }

    @Test
    public void targetItselfNeverCountsAsOccluder() {
        // Target is the surface block (2,1,1); the ray must accept it.
        assertNull(BlockSight.firstBlockingVoxel(CUBE,
                5.5, 1.5, 1.5, 2.5, 1.5, 1.5, 2, 1, 1));
    }

    @Test
    public void adjacentStartVoxelIsIgnored() {
        // Drone clipping the target voxel can still interact with it.
        assertNull(BlockSight.firstBlockingVoxel(CUBE,
                2.4, 1.5, 1.5, 2.5, 1.5, 1.5, 2, 1, 1));
    }

    @Test
    public void diagonalRayDoesNotSlipBetweenSolidVoxels() {
        int[] hit = BlockSight.firstBlockingVoxel(CUBE,
                5.5, 4.5, 4.5, 0.5, 0.5, 0.5, 0, 0, 0);
        assertNotNull(hit);
        assertTrue(voxels(new int[]{2, 2, 2}, new int[]{2, 2, 1}, new int[]{2, 1, 2},
                new int[]{1, 2, 2}).contains(hit[0] + "," + hit[1] + "," + hit[2]));
    }

    @Test
    public void exposedFaceDetectionRequiresOpenNeighbour() {
        // The cube is solid and fully enclosed except through air outside; the
        // neighbour voxels are air so each face is exposed.
        BlockSight.VoxelPassability onlySurfaceAir = (x, y, z) -> x < 0 || x > 2
                || y < 0 || y > 2 || z < 0 || z > 2;
        assertNull(BlockSight.firstBlockingVoxel(onlySurfaceAir,
                3.5, 1.5, 1.5, 2.5, 1.5, 1.5, 2, 1, 1));
    }
}
