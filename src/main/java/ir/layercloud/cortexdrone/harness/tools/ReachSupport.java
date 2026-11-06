package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import lombok.experimental.UtilityClass;
import net.minecraft.world.World;

/**
 * Shared reach check for the block and interaction tools. When the target is
 * out of reach the tool returns {@code needsMovement} plus a safe standoff;
 * the SDK then flies there and repeats the same single operation.
 */
@UtilityClass
final class ReachSupport {
    static final double REACH = 4.5;

    static JsonObject needsMovement(DroneController controller, int x, int y, int z,
                                    String action, double distance) {
        World world = controller.drone.worldObj;
        double[] standoff = FlightPathfinder.findBlockStandoff(world, controller.drone, x, y, z);
        if (standoff == null) {
            int[] open = FlightPathfinder.findNearestPassableVoxel(world, x, y + 1, z, 3);
            if (open == null) {
                return ToolJson.error("No reachable standoff near (" + x + "," + y + "," + z + ")");
            }
            standoff = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
        }
        JsonObject response = ToolJson.ok();
        response.addProperty("needsMovement", true);
        response.addProperty("action", action);
        response.addProperty("distance", Math.round(distance * 100.0) / 100.0);
        response.add("standoff", point(standoff[0], standoff[1], standoff[2]));
        response.add("target", point(x, y, z));
        return response;
    }

    static JsonObject point(double x, double y, double z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", Math.round(x * 100.0) / 100.0);
        point.addProperty("y", Math.round(y * 100.0) / 100.0);
        point.addProperty("z", Math.round(z * 100.0) / 100.0);
        return point;
    }
}
