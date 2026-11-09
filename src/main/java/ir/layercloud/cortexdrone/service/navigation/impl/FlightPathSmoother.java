package ir.layercloud.cortexdrone.service.navigation.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * Reduces a dense A* node chain to a short waypoint list.
 *
 * <p>For each waypoint the smoother probes forward up to {@code lookahead}
 * nodes and jumps to the furthest one with a clear fat line. Extracted from
 * {@code FlightPathfinder.findFlightPath} so the rule is testable without a
 * world: the caller supplies the line-of-sight check.</p>
 */
final class FlightPathSmoother {
    /** How many nodes ahead one smoothing probe may reach. */
    static final int LOOKAHEAD = 16;

    private FlightPathSmoother() {
    }

    /** Line-of-sight check between two points; true when the drone can fly it. */
    interface LineOfSight {
        boolean isClear(double x1, double y1, double z1, double x2, double y2, double z2);
    }

    /**
     * @param raw       node chain from start to goal
     * @param startX    the drone's actual position (not the first node)
     * @param lookahead how many nodes ahead one probe may reach
     * @param sight     line-of-sight test
     * @return the smoothed waypoint list; empty when the path is not progress
     */
    static List<double[]> smooth(List<double[]> raw, double startX, double startY, double startZ,
                                 int lookahead, LineOfSight sight) {
        List<double[]> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        if (raw.size() == 1) {
            // A partial path that never left the start cell is not progress.
            return result;
        }
        if (raw.size() == 2) {
            result.addAll(raw);
            return result;
        }
        int idx = 0;
        while (idx < raw.size() - 1) {
            int furthest = idx + 1;
            double[] from = idx == 0 ? new double[]{startX, startY, startZ} : raw.get(idx);
            int limit = Math.min(raw.size() - 1, idx + Math.max(1, lookahead));
            for (int probe = limit; probe > idx + 1; probe--) {
                double[] candidate = raw.get(probe);
                if (sight.isClear(from[0], from[1], from[2],
                        candidate[0], candidate[1], candidate[2])) {
                    furthest = probe;
                    break;
                }
            }
            result.add(raw.get(furthest));
            idx = furthest;
        }
        return result;
    }
}
