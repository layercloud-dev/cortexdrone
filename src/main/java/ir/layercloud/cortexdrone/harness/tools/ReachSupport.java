package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.world.BlockSight;
import lombok.experimental.UtilityClass;
import net.minecraft.world.World;

/**
 * Shared reachability check for the block and interaction tools.
 *
 * <p>Distance alone is not enough. A drone hovering next to a wall is within
 * 4.5 blocks of the blocks behind it, and the old check happily broke those.
 * Every physical target must also have a clear voxel line of sight from the
 * drone's eye; otherwise the tool reports the occluder and, when a visible
 * standoff exists, asks the caller to move there first.</p>
 */
@UtilityClass
final class ReachSupport {
    static final double REACH = BlockSight.REACH;

    /**
     * Validates that {@code (x,y,z)} can be touched from the drone.
     *
     * @return {@code null} when reachable and visible, otherwise an envelope:
     *         {@code needsMovement} with a visible standoff, or an
     *         {@code out_of_reach}/{@code block_occluded} error.
     */
    static JsonObject unreachable(DroneController controller, int x, int y, int z, String action) {
        if (controller == null || controller.drone == null
                || controller.drone.worldObj == null) {
            return ToolJson.error("no_drone", "No active drone or world.", null, false);
        }
        World world = controller.drone.worldObj;
        BlockSight.Result sight = BlockSight.check(world,
                controller.drone.posX, BlockSight.eyeY(controller.drone), controller.drone.posZ,
                x, y, z);

        if (sight.isVisible() && sight.getDistance() <= REACH) return null;

        double[] standoff = visibleStandoff(controller, x, y, z);
        if (sight.getDistance() > REACH || standoff != null) {
            if (standoff == null) {
                return ToolJson.error("out_of_reach",
                        "Target (" + x + "," + y + "," + z + ") is "
                                + round(sight.getDistance()) + " blocks away and no visible standoff exists.",
                        "Move closer with goto, then retry. If the target is enclosed, break the "
                                + "covering block first.", true);
            }
            return needsMovement(x, y, z, action, sight.getDistance(), standoff);
        }

        int[] occluder = sight.getOccluder();
        JsonObject response = ToolJson.error("block_occluded",
                "Cannot " + action + " (" + x + "," + y + "," + z + "): line of sight is blocked by "
                        + sight.getOccluderName() + ".",
                "This block has no exposed face from the drone. Break the covering block "
                        + (occluder != null ? "at (" + occluder[0] + "," + occluder[1] + "," + occluder[2] + ") " : "")
                        + "first, then retry. Never mine through walls.", true);
        response.add("target", point(x, y, z));
        if (occluder != null) response.add("occluder", point(occluder[0], occluder[1], occluder[2]));
        response.addProperty("distance", round(sight.getDistance()));
        return response;
    }

    private static JsonObject needsMovement(int x, int y, int z, String action,
                                            double distance, double[] standoff) {
        JsonObject response = ToolJson.ok();
        response.addProperty("needsMovement", true);
        response.addProperty("action", action);
        response.addProperty("distance", round(distance));
        response.add("standoff", point(standoff[0], standoff[1], standoff[2]));
        response.add("target", point(x, y, z));
        return response;
    }

    /**
     * Finds a point that is both within reach and has a clear line of sight to
     * the target. Prefers the nearest candidate to the drone.
     */
    private static double[] visibleStandoff(DroneController controller, int x, int y, int z) {
        World world = controller.drone.worldObj;
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;
        int[][] directions = new int[][]{
                {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
        };
        for (int[] direction : directions) {
            int ax = x + direction[0];
            int ay = y + direction[1];
            int az = z + direction[2];
            if (!BlockSight.isOpenForDrone(world, ax, ay, az)) continue;
            double[] candidate = standoffPoint(ax, ay, az);
            if (!isVisibleFrom(controller, candidate, x, y, z)) continue;
            double distance = controller.drone.getDistance(candidate[0], candidate[1], candidate[2]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best != null) return best;

        // Exposed faces can also be reached diagonally (a corner or a ledge).
        for (int radius = 1; radius <= 2 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int ax = x + dx;
                        int ay = y + dy;
                        int az = z + dz;
                        if (!BlockSight.isOpenForDrone(world, ax, ay, az)) continue;
                        double[] candidate = standoffPoint(ax, ay, az);
                        if (!isVisibleFrom(controller, candidate, x, y, z)) continue;
                        double distance = controller.drone.getDistance(candidate[0], candidate[1], candidate[2]);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isVisibleFrom(DroneController controller, double[] point,
                                         int x, int y, int z) {
        double eyeY = point[1] + BlockSight.DRONE_EYE_HEIGHT;
        BlockSight.Result sight = BlockSight.check(controller.drone.worldObj,
                point[0], eyeY, point[2], x, y, z);
        return sight.isVisible() && sight.getDistance() <= REACH;
    }

    private static double[] standoffPoint(int x, int y, int z) {
        return new double[]{x + 0.5D, y + 0.35D, z + 0.5D};
    }

    static JsonObject point(double x, double y, double z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", Math.round(x * 100.0) / 100.0);
        point.addProperty("y", Math.round(y * 100.0) / 100.0);
        point.addProperty("z", Math.round(z * 100.0) / 100.0);
        return point;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
