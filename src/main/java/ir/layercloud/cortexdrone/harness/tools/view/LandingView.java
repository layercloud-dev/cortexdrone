package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.navigation.LandingSpot;
import ir.layercloud.cortexdrone.service.navigation.impl.LandingScanner;
import net.minecraft.util.MathHelper;

/**
 * JSON boundary for the read-only {@code find_safe_landing} query.
 *
 * <p>Call this before dismounting a passenger and whenever a destination looks
 * like a mountain, tree, water or lava. It never changes the world.</p>
 */
public final class LandingView {

    public JsonObject handle(DroneController controller, JsonObject args) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return ToolJson.error("no_drone", "No active drone or world.", null, false);
        }
        int x = args != null && args.has("x") ? args.get("x").getAsInt()
                : MathHelper.floor_double(controller.drone.posX);
        int z = args != null && args.has("z") ? args.get("z").getAsInt()
                : MathHelper.floor_double(controller.drone.posZ);
        int refY = args != null && args.has("y") ? args.get("y").getAsInt()
                : MathHelper.floor_double(controller.drone.posY);
        int radius = args != null && args.has("radius")
                ? args.get("radius").getAsInt() : LandingScanner.DEFAULT_RADIUS;
        radius = Math.max(LandingScanner.MIN_RADIUS,
                Math.min(LandingScanner.MAX_RADIUS, radius));
        // A passenger is only flown into open sky: entering a cave or a
        // two-high corridor crushes them. `covered_ok:true` is for a mob or a
        // drone-only landing where a cave floor is acceptable.
        boolean requireOpenSky = args == null || !args.has("covered_ok")
                || !args.get("covered_ok").getAsBoolean();

        LandingSpot best = LandingScanner.findSafeSpot(
                controller.drone.worldObj, x, z, refY, radius, requireOpenSky);
        LandingSpot requested = LandingScanner.inspectColumn(
                controller.drone.worldObj, x, z, refY);

        JsonObject response = ToolJson.ok();
        response.addProperty("requestedX", x);
        response.addProperty("requestedZ", z);
        response.addProperty("referenceY", refY);
        response.addProperty("radius", radius);
        response.addProperty("safeAtRequested", requested != null && requested.isSafe());
        response.add("requested", spotJson(requested, x, z));
        response.addProperty("requireOpenSky", requireOpenSky);
        if (best == null) {
            response.addProperty("found", false);
            response.addProperty("safe", false);
            response.addProperty("hint", "No safe landing column in radius. Fly to a different "
                    + "area (higher, over open ground) or pass a larger radius. A cave floor is "
                    + "only accepted with covered_ok:true, and never with a passenger.");
            return response;
        }
        response.addProperty("found", true);
        response.addProperty("safe", true);
        response.addProperty("movedFromRequest", best.getX() != x || best.getZ() != z);
        response.add("spot", spotJson(best, x, z));
        return response;
    }

    private static JsonObject spotJson(LandingSpot spot, int reqX, int reqZ) {
        JsonObject json = new JsonObject();
        if (spot == null) {
            json.addProperty("safe", false);
            json.addProperty("reason", "chunk unavailable");
            return json;
        }
        json.addProperty("x", spot.getX());
        json.addProperty("y", spot.getY());
        json.addProperty("z", spot.getZ());
        // Dismount exactly in the feet cell, never above it: a rider dropped
        // one block up still takes a fall, and two blocks up hurts.
        json.addProperty("hoverX", spot.getX() + DroneGeometry.CELL_CENTER);
        json.addProperty("hoverY", spot.getY() + DroneGeometry.AIM_OFFSET);
        json.addProperty("hoverZ", spot.getZ() + DroneGeometry.CELL_CENTER);
        json.addProperty("safe", spot.isSafe());
        json.addProperty("openSky", spot.isOpenSky());
        json.addProperty("surfaceLevel", spot.isSurfaceLevel());
        json.addProperty("floor", spot.getFloorBlock());
        json.addProperty("reason", spot.getReason());
        json.addProperty("distance", Math.round(Math.sqrt(
                (spot.getX() - reqX) * (spot.getX() - reqX)
                        + (spot.getZ() - reqZ) * (spot.getZ() - reqZ)) * 10.0) / 10.0);
        return json;
    }
}
