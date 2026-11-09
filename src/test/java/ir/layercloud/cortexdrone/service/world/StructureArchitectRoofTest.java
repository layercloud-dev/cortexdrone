package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Roof geometry must cover every column of the footprint.
 *
 * <p>Regression: the gable roof used {@code layers = (span + 1) / 2}, which
 * stops one layer short on an odd span and leaves a one-block hole along the
 * ridge for every odd-sized footprint.</p>
 */
public class StructureArchitectRoofTest {
    private static StructurePlan plan(int x1, int z1, int x2, int z2) {
        return new StructurePlan(x1, z1, x2, z2, 64, 80,
                "north", "house", "gable");
    }

    private static Set<String> roofColumns(StructurePlan plan) {
        Set<String> columns = new HashSet<>();
        for (BuildTask task : plan.getBuildTasks()) {
            if (BuildTask.ROOF.equals(task.getPhase())) {
                columns.add(task.getX() + "," + task.getZ());
            }
        }
        return columns;
    }

    private static void assertEveryColumnCovered(int side) {
        StructurePlan plan = plan(-side / 2, -side / 2,
                -side / 2 + side - 1, -side / 2 + side - 1);
        StructureArchitect.addRoofForTest(plan, plan.getX1(), plan.getZ1(),
                plan.getX2(), plan.getZ2(), 70, "gable", "oak_planks");
        Set<String> covered = roofColumns(plan);
        for (int x = plan.getX1(); x <= plan.getX2(); x++) {
            for (int z = plan.getZ1(); z <= plan.getZ2(); z++) {
                assertTrue("roof must cover column " + x + "," + z + " for a "
                                + side + "x" + side + " footprint",
                        covered.contains(x + "," + z));
            }
        }
    }

    @Test
    public void gableRoofCoversEveryOddFootprint() {
        for (int side = 3; side <= 19; side += 2) {
            assertEveryColumnCovered(side);
        }
    }

    @Test
    public void gableRoofCoversEveryEvenFootprint() {
        for (int side = 4; side <= 18; side += 2) {
            assertEveryColumnCovered(side);
        }
    }

    @Test
    public void roofLayerCountMatchesThePlacedLayers() {
        // maxY is derived from roofLayers(); a mismatch means the clearing pass
        // either leaves roof blocks behind or clears air above the roof.
        for (int side = 3; side <= 19; side++) {
            int layers = StructureArchitect.roofLayersForTest(side, side, "gable");
            StructurePlan plan = plan(0, 0, side - 1, side - 1);
            StructureArchitect.addRoofForTest(plan, 0, 0, side - 1, side - 1, 70, "gable", "oak_planks");
            int highest = Integer.MIN_VALUE;
            for (BuildTask task : plan.getBuildTasks()) {
                if (BuildTask.ROOF.equals(task.getPhase())) highest = Math.max(highest, task.getY());
            }
            assertEquals("the highest roof task must sit at base + layers - 1 for a "
                            + side + "x" + side + " footprint",
                    70 + layers - 1, highest);
        }
    }
}
