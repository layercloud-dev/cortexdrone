package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The distant-resource search advertises a 10 s tool budget but must give
 * itself a comparable internal deadline. A 12 ms budget aborted after one ring
 * and reported "not found" for almost every far target.
 */
public class WorldScannerBudgetTest {
    @Test
    public void searchBudgetIsComparableToTheToolTimeout() {
        assertTrue("a distant ring walk needs more than 12 ms, was "
                        + WorldScanBudget.SEARCH_BUDGET_MS,
                WorldScanBudget.SEARCH_BUDGET_MS >= 2_000L);
    }

    @Test
    public void searchBudgetStaysInsideTheDeclaredToolTimeout() {
        assertTrue("must not outlive the 10 s tool timeout",
                WorldScanBudget.SEARCH_BUDGET_MS <= 9_000L);
    }

    @Test
    public void searchGeometryConstantsAreSane() {
        assertTrue(WorldScanBudget.SEARCH_MIN_RADIUS > 0);
        assertTrue(WorldScanBudget.SEARCH_RADIUS_STEP > 0);
        assertTrue(WorldScanBudget.SEARCH_SAMPLE_RADIUS >= 0);
        assertTrue(WorldScanBudget.SEARCH_MAX_CHUNK_LOADS > 0);
    }
}
