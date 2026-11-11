package ir.layercloud.cortexdrone.service.navigation.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Step-cost and movement-legality rules for the 3D flight A*.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. The cost model is
 * what makes A* prefer air over lava and solid floors over open drop-offs, so it
 * is pinned here rather than only exercised through a live world.</p>
 */
public class FlightStepRulesTest {
    @Test
    public void orthogonalStepsAreCheapest() {
        assertEquals(1.0, FlightStepRules.baseCost(1, 0, 0), 0.0001);
        assertEquals(1.0, FlightStepRules.baseCost(0, 1, 0), 0.0001);
        assertEquals(1.0, FlightStepRules.baseCost(0, 0, -1), 0.0001);
    }

    @Test
    public void diagonalStepsUseTheGeometricDistance() {
        assertEquals(1.414, FlightStepRules.baseCost(1, 1, 0), 0.0001);
        assertEquals(1.414, FlightStepRules.baseCost(1, 0, 1), 0.0001);
        assertEquals(1.732, FlightStepRules.baseCost(1, 1, 1), 0.0001);
        assertEquals(1.732, FlightStepRules.baseCost(-1, 1, -1), 0.0001);
    }

    @Test
    public void noStepCostsNothing() {
        assertEquals(0.0, FlightStepRules.baseCost(0, 0, 0), 0.0001);
    }

    @Test
    public void costIsSymmetric() {
        assertEquals(FlightStepRules.baseCost(1, 1, 0), FlightStepRules.baseCost(-1, -1, 0), 0.0001);
        assertEquals(FlightStepRules.baseCost(1, 0, 1), FlightStepRules.baseCost(-1, 0, -1), 0.0001);
    }

    @Test
    public void manhattanDistanceCountsNonZeroAxes() {
        assertEquals(1, FlightStepRules.manhattan(1, 0, 0));
        assertEquals(2, FlightStepRules.manhattan(1, -1, 0));
        assertEquals(3, FlightStepRules.manhattan(1, 1, 1));
        assertEquals(0, FlightStepRules.manhattan(0, 0, 0));
    }

    @Test
    public void lavaCostsMoreThanOpenAir() {
        assertTrue("lava must dominate the step cost",
                FlightPathfinder.LAVA_STEP_COST > FlightPathfinder.BASE_STEP_COST);
        assertEquals(8.0, FlightPathfinder.LAVA_STEP_COST, 0.0001);
        assertEquals(2.0, FlightPathfinder.BASE_STEP_COST, 0.0001);
    }

    @Test
    public void aStepWithoutGroundCostsMoreThanOneWithGround() {
        double grounded = FlightStepRules.withPenalties(1.0, false, false, true);
        double floating = FlightStepRules.withPenalties(1.0, false, false, false);
        assertTrue("a drop-off must not be preferred over a solid floor", floating > grounded);
        assertEquals(1.0, grounded, 0.0001);
    }

    @Test
    public void liquidAndGroundPenaltiesAccumulate() {
        assertEquals(1.0 + FlightPathfinder.LAVA_STEP_COST + 0.35,
                FlightStepRules.withPenalties(1.0, true, true, false), 0.0001);
        assertEquals(1.0 + FlightPathfinder.BASE_STEP_COST,
                FlightStepRules.withPenalties(1.0, true, false, true), 0.0001);
    }
}
