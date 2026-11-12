package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.SourceTree;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The flight search's published limits are a single source of truth.
 *
 * <p>Regression risk: the node cap, wall-clock budget and Theta* distance used to
 * be declared in both the orchestrator and the search, so tuning one left the
 * other stale. They now live only in {@link FlightAStar}.</p>
 */
public class FlightSearchLimitsTest {
    @Test
    public void theSearchBudgetIsBounded() {
        assertTrue("a plan must not run unbounded on the server tick",
                FlightAStar.MAX_EXPANSIONS > 0);
        assertTrue("the wall-clock budget must be positive",
                FlightAStar.SEARCH_BUDGET_NANOS > 0L);
        assertTrue("a tick-sized budget", FlightAStar.SEARCH_BUDGET_NANOS < 50_000_000L);
    }

    @Test
    public void partialPathsHaveBothMinimums() {
        assertTrue("a useful partial is at least a few blocks",
                FlightAStar.MIN_PARTIAL_DIST > 0.0);
        assertTrue("a tight pocket still needs a fallback",
                FlightAStar.MIN_PARTIAL_STEP > 0.0);
        assertTrue("the fallback is shorter than the preferred minimum",
                FlightAStar.MIN_PARTIAL_STEP < FlightAStar.MIN_PARTIAL_DIST);
    }

    @Test
    public void stepCostsAreOrdered() {
        assertTrue("lava must dominate a normal liquid step",
                FlightPathfinder.LAVA_STEP_COST > FlightPathfinder.BASE_STEP_COST);
        assertTrue(FlightPathfinder.BASE_STEP_COST > 0.0);
    }

    @Test
    public void smoothingLookaheadIsBounded() {
        assertTrue(FlightPathSmoother.LOOKAHEAD > 1);
    }

    @Test
    public void theSearchLimitsAreNotRedeclaredInTheOrchestrator() {
        // A structural guard, kept here so the two copies cannot come back.
        String orchestrator = SourceTree.require(
                SourceTree.MAIN
                        + "service/navigation/impl/FlightPathfinder.java");
        assertFalse("the node cap belongs to FlightAStar",
                orchestrator.contains("MAX_EXPANSIONS ="));
        assertFalse("the budget belongs to FlightAStar",
                orchestrator.contains("SEARCH_BUDGET_NANOS ="));
        assertFalse("the backoff coefficients belong to FlightAStar",
                orchestrator.contains("BARITONE_COEFFICIENTS"));
    }
}
