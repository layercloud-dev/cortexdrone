package ir.layercloud.cortexdrone.service.navigation.impl;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Path simplification rules for the 3D flight A*.
 *
 * <p>Extracted from {@code FlightPathfinder.findFlightPath}. The smoother must
 * keep the first and last waypoint, must never invent a waypoint, and must
 * return an empty list when the raw path never left the start cell — that last
 * case used to produce a one-waypoint "path" that immediately blocked and burned
 * the recovery budget.</p>
 */
public class FlightPathSmootherTest {
    /** Every line is "clear", so smoothing takes the furthest probe. */
    private static boolean alwaysClear(double x1, double y1, double z1,
                                       double x2, double y2, double z2) {
        return true;
    }

    /** Nothing is clear, so smoothing must keep every raw waypoint. */
    private static boolean neverClear(double x1, double y1, double z1,
                                      double x2, double y2, double z2) {
        return false;
    }

    private static List<double[]> raw(int count) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < count; i++) points.add(new double[]{i + 0.5, 64.65, 0.5});
        return points;
    }

    @Test
    public void aTwoPointPathIsPassedThroughUnchanged() {
        List<double[]> input = raw(2);
        List<double[]> result = FlightPathSmoother.smooth(input, 64.0, 64.65, 0.5,
                8, FlightPathSmootherTest::alwaysClear);
        assertEquals(2, result.size());
        assertSame(input.get(0), result.get(0));
        assertSame(input.get(1), result.get(1));
    }

    @Test
    public void aClearPathCollapsesToItsEndpoints() {
        List<double[]> result = FlightPathSmoother.smooth(raw(10), 64.0, 64.65, 0.5,
                8, FlightPathSmootherTest::alwaysClear);
        assertEquals("a fully clear path needs only its endpoints", 2, result.size());
    }

    @Test
    public void aBlockedPathKeepsEveryWaypoint() {
        List<double[]> result = FlightPathSmoother.smooth(raw(6), 64.0, 64.65, 0.5,
                8, FlightPathSmootherTest::neverClear);
        assertEquals(5, result.size());
    }

    @Test
    public void smoothingNeverInventsAWaypoint() {
        List<double[]> input = raw(6);
        List<double[]> result = FlightPathSmoother.smooth(input, 64.0, 64.65, 0.5,
                8, FlightPathSmootherTest::alwaysClear);
        for (double[] point : result) {
            boolean fromInput = false;
            for (double[] candidate : input) {
                if (Arrays.equals(candidate, point)) fromInput = true;
            }
            assertTrue("smoothed waypoints must come from the raw path", fromInput);
        }
    }

    @Test
    public void aSingleWaypointPathIsNotProgress() {
        assertTrue("a path that never left the start cell must be empty",
                FlightPathSmoother.smooth(raw(1), 0.5, 64.65, 0.5,
                        8, FlightPathSmootherTest::alwaysClear).isEmpty());
    }

    @Test
    public void anEmptyPathStaysEmpty() {
        assertTrue(FlightPathSmoother.smooth(new ArrayList<>(), 0, 0, 0,
                8, FlightPathSmootherTest::alwaysClear).isEmpty());
        assertTrue(FlightPathSmoother.smooth(null, 0, 0, 0,
                8, FlightPathSmootherTest::alwaysClear).isEmpty());
    }

    @Test
    public void theLookaheadBoundsHowFarAProbeReaches() {
        List<double[]> once = FlightPathSmoother.smooth(raw(10), 0.5, 64.65, 0.5,
                1, FlightPathSmootherTest::alwaysClear);
        assertEquals("a 1-point lookahead can only step to the next node", 9, once.size());
    }
}
