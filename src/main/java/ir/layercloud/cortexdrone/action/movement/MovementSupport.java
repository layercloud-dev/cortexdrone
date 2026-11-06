package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.action.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import lombok.Value;
import lombok.experimental.UtilityClass;
import net.minecraft.util.MathHelper;

/** Pure target/route calculations shared by movement actions. */
@UtilityClass
public final class MovementSupport {
    public static final String ROUTE_AUTO = "auto";
    public static final String ROUTE_DIRECT = "direct";
    public static final String ROUTE_TUNNEL = "tunnel";
    public static final String ROUTE_UP = "up";
    public static final String ROUTE_DOWN = "down";

    @Value
    public static class Target {
        double x;
        double y;
        double z;
    }

    public static Target resolveTarget(MovementRequest request) {
        DroneController controller = request != null ? request.getController() : null;
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        if (request.isStandoff()) {
            double[] standoff = FlightPathfinder.findMiningStandoff(controller.drone.worldObj,
                controller.drone, request.getStandoffX(), request.getStandoffY(), request.getStandoffZ());
            if (standoff == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(controller.drone.worldObj,
                    request.getStandoffX(), request.getStandoffY() + 1, request.getStandoffZ(), 3);
                if (open == null) return null;
                standoff = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            return new Target(standoff[0], standoff[1], standoff[2]);
        }
        if (request.isCoordinates()) {
            return new Target(request.getX(), request.getY(), request.getZ());
        }
        return null;
    }

    public static String route(String requested, String fallback) {
        if (requested == null) return fallback;
        String route = requested.trim().toLowerCase();
        if (!ROUTE_AUTO.equals(route) && !ROUTE_DIRECT.equals(route)
            && !ROUTE_TUNNEL.equals(route) && !ROUTE_UP.equals(route)
            && !ROUTE_DOWN.equals(route)) {
            return fallback;
        }
        return route;
    }

    public static Target surfaceTarget(DroneController controller) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        int x = MathHelper.floor_double(controller.drone.posX);
        int z = MathHelper.floor_double(controller.drone.posZ);
        int top = controller.drone.worldObj.getHeightValue(x, z);
        return new Target(x + 0.5, Math.min(250, top + 2.5), z + 0.5);
    }

    public static Target depthTarget(DroneController controller, int depth) {
        if (controller == null || controller.drone == null) return null;
        int x = MathHelper.floor_double(controller.drone.posX);
        int y = Math.max(1, MathHelper.floor_double(controller.drone.posY) - Math.max(1, depth));
        int z = MathHelper.floor_double(controller.drone.posZ);
        return new Target(x + 0.5, y, z + 0.5);
    }


    /** Advances one navigation action and completes its context on arrival. */
    public static void tick(MovementContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            context.complete();
            return;
        }

        double x = controller.drone.posX;
        double y = controller.drone.posY;
        double z = controller.drone.posZ;
        double dx = x - context.goalX();
        double dy = y - context.goalY();
        double dz = z - context.goalZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        context.recordProgress(x, y, z, distance);

        if (controller.mode != Mode.GOTO || controller.navigationGeneration != context.generation()) {
            context.finish(controller.mode != Mode.GOTO && distance <= 1.25);
        }
    }

    public static MovementResult start(AIDroneManager manager, MovementRequest request,
                                       Target target, String route, String label) {
        DroneController controller = request != null ? request.getController() : null;
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return MovementResult.failure("No active drone");
        }

        double goalX = target.getX();
        double goalY = target.getY();
        double goalZ = target.getZ();
        controller.navigationGeneration++;
        controller.navigationRoute = route;
        controller.navViaSurface = false;
        controller.navFinalX = Double.NaN;
        controller.navFinalY = Double.NaN;
        controller.navFinalZ = Double.NaN;

        if (ROUTE_UP.equals(route)) {
            int x = MathHelper.floor_double(controller.drone.posX);
            int y = MathHelper.floor_double(controller.drone.posY);
            int z = MathHelper.floor_double(controller.drone.posZ);
            boolean sky = controller.drone.worldObj.canBlockSeeTheSky(x, y, z)
                || y >= controller.drone.worldObj.getHeightValue(x, z) - 1;
            if (!sky) {
                controller.navFinalX = goalX;
                controller.navFinalY = goalY;
                controller.navFinalZ = goalZ;
                int top = controller.drone.worldObj.getHeightValue(x, z);
                goalX = x + 0.5;
                goalY = Math.min(250, top + 2.5);
                goalZ = z + 0.5;
                controller.navViaSurface = true;
            }
        }

        NavContext navigation = NavContext.of(controller, goalX, goalY, goalZ, route);
        navigation.setGoal(goalX, goalY, goalZ);
        manager.setDroneMode(controller, Mode.GOTO,
            label + " " + route + String.format(" (%.1f,%.1f,%.1f)", goalX, goalY, goalZ));
        return MovementResult.started(route, goalX, goalY, goalZ, controller.navViaSurface);
    }
}
