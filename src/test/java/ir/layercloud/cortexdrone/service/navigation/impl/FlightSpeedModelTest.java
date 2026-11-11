package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Flies the real integrator and measures the achieved speed.
 *
 * <p>The 5x cap is only real if the drone can actually reach it. A live flight
 * measured ~20 b/s because the sub-leg stride never let the drone leave the
 * braking zone, so the cap was unreachable. These tests simulate the shipped
 * arithmetic from {@link FlightSpeedModel} — the same code
 * {@code DronePhysicsService} runs on the server thread — and assert the
 * distance actually covered per tick.</p>
 */
public class FlightSpeedModelTest {
    /**
     * Drone drag in air; OC's drone uses 0.91.
     */
    private static final double DRAG = 0.91;
    private static final float ACCEL_FLOOR = 0.1f;

    /**
     * Flies toward a goal for up to {@code maxTicks}, re-targeting the sub-goal
     * exactly like {@code NavigateAction} does: a stride ahead, re-planned when
     * within the arrival radius. Returns the ticks used.
     */
    private static int fly(FlightSpeedModel.State state, double goalX, double goalY,
                           double goalZ, double multiplier, int maxTicks) {
        for (int tick = 1; tick <= maxTicks; tick++) {
            double dx = goalX - state.x;
            double dy = goalY - state.y;
            double dz = goalZ - state.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= DroneSpeed.arrivalRadius(multiplier)) return tick;

            // Sub-goal: one stride ahead, clamped to the goal (NavigateAction).
            double stride = DroneSpeed.stride(multiplier, 16.0);
            double horiz = Math.hypot(dx, dz);
            double tx = goalX;
            double tz = goalZ;
            double ty = goalY;
            if (horiz > stride) {
                tx = state.x + dx / horiz * stride;
                tz = state.z + dz / horiz * stride;
                ty = state.y + dy * (stride / distance);
            }

            double cap = DroneSpeed.resolve(distance, multiplier, multiplier, false);
            FlightSpeedModel.step(state, tx, ty, tz, cap, false, DRAG, ACCEL_FLOOR);
        }
        return -1;
    }

    @Test
    public void aFiveTimesDroneActuallyTravelsFiveTimesFaster() {
        double goalX = 600.0;

        FlightSpeedModel.State baseline = new FlightSpeedModel.State(0, 64, 0);
        int baselineTicks = fly(baseline, goalX, 64, 0, 1.0, 20_000);
        assertTrue("the baseline flight must finish", baselineTicks > 0);

        FlightSpeedModel.State fast = new FlightSpeedModel.State(0, 64, 0);
        int fastTicks = fly(fast, goalX, 64, 0, 5.0, 20_000);
        assertTrue("the boosted flight must finish", fastTicks > 0);

        double ratio = (double) baselineTicks / fastTicks;
        assertTrue("5x must be dramatically faster, got ratio " + ratio
                        + " (baseline " + baselineTicks + " ticks, fast " + fastTicks + ")",
                ratio >= 3.5);
    }

    @Test
    public void theBoostedCruiseActuallyHoldsNearTheCap() {
        // Fly a long straight leg and inspect the sustained speed in the middle.
        // Displacement per tick is the true speed; stored motion carries drag.
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double cap = DroneSpeed.resolve(10_000, 5.0, 5.0, false);
        double peak = 0;
        for (int i = 0; i < 200; i++) {
            double before = state.x;
            FlightSpeedModel.step(state, 10_000, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
            peak = Math.max(peak, state.x - before);
        }
        assertTrue("cruise must reach the cap: peak=" + peak + " cap=" + cap,
                peak >= cap * 0.98);
        assertTrue("cruise must not exceed the cap", peak <= cap + 1e-6);
    }

    @Test
    public void theOldFixedStrideWouldHaveCappedTheSpeed() {
        // Reproduces the live bug: cap 5x but a 16-block stride. The drone
        // enters ease-in before it can accelerate, so the average stays low.
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double cap = DroneSpeed.resolve(10_000, 5.0, 5.0, false);
        double peak = 0;
        for (int i = 0; i < 400; i++) {
            // 16-block sub-goal, the pre-fix value.
            double stride = 16.0;
            double dx = 10_000 - state.x;
            double tx = state.x + Math.min(stride, dx);
            FlightSpeedModel.step(state, tx, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
            peak = Math.max(peak, state.speed());
        }
        assertTrue("a fixed 16-block stride cannot sustain the 5x cap: peak=" + peak,
                peak < cap * 0.75);
    }

    @Test
    public void aCarriedPassengerIsFlownAtBaselineSpeed() {
        double riderCap = DroneSpeed.resolve(10_000, 5.0, 0, true);
        assertEquals(DroneSpeed.FAR, riderCap, 1e-9);

        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double peak = 0;
        for (int i = 0; i < 200; i++) {
            FlightSpeedModel.step(state, 10_000, 64, 0, riderCap, false, DRAG, ACCEL_FLOOR);
            peak = Math.max(peak, state.speed());
        }
        assertTrue("a rider must never exceed the tuned speed: " + peak,
                peak <= DroneSpeed.FAR + 1e-6);
    }

    @Test
    public void waterStillCapsSpeedRegardlessOfTheMultiplier() {
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double cap = DroneSpeed.resolve(10_000, 5.0, 5.0, false);
        double peak = 0;
        for (int i = 0; i < 200; i++) {
            double before = state.x;
            FlightSpeedModel.step(state, 10_000, 64, 0, cap, true, 0.86, ACCEL_FLOOR);
            peak = Math.max(peak, state.x - before);
        }
        assertTrue("water must cap speed at 0.34: " + peak, peak <= 0.34 + 1e-6);
    }

    @Test
    public void theDroneStopsAtTheGoalWithoutOvershooting() {
        // Close-range approach: the arrival radius must be reachable and the
        // drone must not oscillate around the point.
        double goal = 20.0;
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double arrival = DroneSpeed.arrivalRadius(5.0);
        int ticks = 0;
        for (; ticks < 2_000; ticks++) {
            double distance = Math.abs(goal - state.x);
            if (distance <= arrival) break;
            double cap = DroneSpeed.resolve(distance, 5.0, 5.0, false);
            FlightSpeedModel.step(state, goal, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
        }
        assertTrue("the drone must settle at the goal, ticks=" + ticks, ticks < 2_000);
        assertTrue("and must not overshoot far past it: x=" + state.x,
                state.x <= goal + arrival + 1.0);
    }

    @Test
    public void aSlowMultiplierIsHonoured() {
        double cap = DroneSpeed.resolve(10_000, 0.5, 0.5, false);
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        double peak = 0;
        for (int i = 0; i < 300; i++) {
            double before = state.x;
            FlightSpeedModel.step(state, 10_000, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
            peak = Math.max(peak, state.x - before);
        }
        assertTrue("half speed must be respected: peak=" + peak + " cap=" + cap,
                peak <= cap + 1e-6);
        assertTrue("and must still make progress", peak > cap * 0.5);
    }

    @Test
    public void theMinimumEaseInFloorNeverExceedsASlowCap() {
        // 0.25x of the far tier is 0.1875 blocks/tick; the historical 0.28 floor
        // made that setting actually fly 1.5x faster than requested.
        double cap = DroneSpeed.resolve(10_000, 0.25, 0.25, false);
        assertEquals(DroneSpeed.FAR * 0.25, cap, 1e-9);
        FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
        for (int i = 0; i < 300; i++) {
            double before = state.x;
            FlightSpeedModel.step(state, 10_000, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
            assertTrue("speed " + (state.x - before) + " exceeded the slow cap " + cap,
                    state.x - before <= cap + 1e-6);
        }
    }

    @Test
    public void theCapIsNeverExceededAtAnySupportedMultiplier() {
        for (double m = 0.25; m <= 5.0; m += 0.25) {
            double cap = DroneSpeed.resolve(10_000, m, m, false);
            FlightSpeedModel.State state = new FlightSpeedModel.State(0, 64, 0);
            for (int i = 0; i < 200; i++) {
                double before = state.x;
                FlightSpeedModel.step(state, 10_000, 64, 0, cap, false, DRAG, ACCEL_FLOOR);
                double moved = state.x - before;
                assertTrue("speed " + moved + " exceeded cap " + cap + " at " + m + "x",
                        moved <= cap + 1e-6);
            }
        }
    }
}
