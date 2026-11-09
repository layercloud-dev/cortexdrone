package ir.layercloud.cortexdrone.service.navigation.impl;

/**
 * Movement-legality and step-cost rules for the 3D flight A*.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. Keeping the cost
 * model here means the search loop reads as search, and the "what does a step
 * cost" question has one answer that can be tested without a world.</p>
 */
final class FlightStepRules {
    /** Extra cost when the step has no floor below it. */
    static final double NO_GROUND_PENALTY = 0.35D;

    private FlightStepRules() {
    }

    /** Number of non-zero axes in a step (1 = face, 2 = edge, 3 = corner). */
    static int manhattan(int dx, int dy, int dz) {
        int count = 0;
        if (dx != 0) count++;
        if (dy != 0) count++;
        if (dz != 0) count++;
        return count;
    }

    /** Geometric length of a unit step along the non-zero axes. */
    static double baseCost(int dx, int dy, int dz) {
        switch (manhattan(dx, dy, dz)) {
            case 1:
                return 1.0D;
            case 2:
                return 1.414D;
            case 3:
                return 1.732D;
            default:
                return 0.0D;
        }
    }

    /**
     * Adds the liquid and no-ground penalties to a base cost.
     *
     * @param inLiquid      the destination cell holds a liquid
     * @param dangerous     that liquid is lava or another damaging fluid
     * @param hasGround     a solid block sits directly below the destination
     */
    static double withPenalties(double baseCost, boolean inLiquid, boolean dangerous,
                                boolean hasGround) {
        double cost = baseCost;
        if (inLiquid) {
            cost += dangerous ? FlightPathfinder.LAVA_STEP_COST : FlightPathfinder.BASE_STEP_COST;
        }
        if (!hasGround) cost += NO_GROUND_PENALTY;
        return cost;
    }
}
