package ir.layercloud.cortexdrone.service.navigation.impl;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Flight movement legality: the drone occupies two voxels and must not clip a
 * corner.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. The head-layer and
 * corner checks were the fix for diagonal moves clipping solid head-height
 * blocks and then velocity-blocking, so they are pinned here.</p>
 */
public class FlightMovementRulesTest {
    /** A voxel set where only the listed cells are passable. */
    private static FlightMovementRules.Voxels open(Set<String> passable) {
        return (x, y, z) -> passable.contains(x + "," + y + "," + z);
    }

    private static Set<String> cells(int... xyz) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            out.add(xyz[i] + "," + xyz[i + 1] + "," + xyz[i + 2]);
        }
        return out;
    }

    @Test
    public void anOpenOrthogonalStepIsLegal() {
        // feet (1,64,0) and head (1,65,0) are free
        FlightMovementRules.Voxels voxels = open(cells(1, 64, 0, 1, 65, 0));
        assertTrue(FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 0, 0));
    }

    @Test
    public void aBlockedHeadCellMakesAFeetClearStepIllegal() {
        // Feet cell free, head cell solid: the drone would clip the ceiling.
        FlightMovementRules.Voxels voxels = open(cells(1, 64, 0));
        assertFalse("a solid head cell must reject the step",
                FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 0, 0));
    }

    @Test
    public void aBlockedDiagonalCornerIsRejected() {
        // Destination and head are free, but the (1,64,0) corner is solid.
        FlightMovementRules.Voxels voxels = open(cells(1, 64, 1, 1, 65, 1));
        assertFalse("a solid corner must reject the diagonal",
                FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 0, 1));
    }

    @Test
    public void aClearDiagonalIsLegal() {
        // Every cell the rule requires: destination feet+head, both feet-level
        // faces, and both head-level faces.
        FlightMovementRules.Voxels voxels = open(cells(
                1, 64, 1, 1, 65, 1,
                1, 64, 0, 0, 64, 1,
                1, 65, 0, 0, 65, 1));
        assertTrue(FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 0, 1));
    }

    @Test
    public void aBlockedHeadHeightCornerIsRejected() {
        // Feet-level corner free, but the head-level face at (1,65,0) is solid.
        FlightMovementRules.Voxels voxels = open(cells(
                1, 64, 1, 1, 65, 1,
                1, 64, 0,
                0, 64, 1));
        assertFalse("a solid block at head height must reject the diagonal",
                FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 0, 1));
    }

    @Test
    public void aCornerStepChecksTheInnerEdges() {
        // dx=dy=dz=1 from (0,64,0): destination is (1,65,1), head (1,66,1).
        FlightMovementRules.Voxels voxels = open(cells(
                1, 65, 1, 1, 66, 1,
                1, 64, 0, 0, 65, 0, 0, 64, 1,
                1, 65, 0, 0, 66, 0, 0, 65, 1,
                1, 65, 0, 1, 64, 1, 0, 65, 1));
        assertTrue(FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 1, 1));
    }

    @Test
    public void aCornerStepWithABlockedInnerEdgeIsRejected() {
        // Same as above but (1,64,1) (the dy inner edge) is solid.
        FlightMovementRules.Voxels voxels = open(cells(
                1, 65, 1, 1, 66, 1,
                1, 64, 0, 0, 65, 0, 0, 64, 1,
                1, 65, 0, 0, 66, 0, 0, 65, 1,
                1, 65, 0, 0, 65, 1));
        assertFalse("a solid inner edge must reject a corner step",
                FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 1, 1, 1));
    }

    @Test
    public void aZeroStepIsNotAMove() {
        FlightMovementRules.Voxels voxels = open(cells(0, 64, 0, 0, 65, 0));
        assertTrue("a zero step is trivially legal", FlightMovementRules.isStepLegal(voxels, 0, 64, 0, 0, 0, 0));
    }

    @Test
    public void nullVoxelsAreNeverLegal() {
        assertFalse(FlightMovementRules.isStepLegal(null, 0, 64, 0, 1, 0, 0));
        assertFalse(FlightMovementRules.isPositionLegal(null, 0, 64, 0));
    }

    @Test
    public void aPositionNeedsBothLayersFree() {
        assertTrue(FlightMovementRules.isPositionLegal(open(cells(0, 64, 0, 0, 65, 0)), 0, 64, 0));
        assertFalse("a feet-only position is not standable",
                FlightMovementRules.isPositionLegal(open(cells(0, 64, 0)), 0, 64, 0));
    }
}
