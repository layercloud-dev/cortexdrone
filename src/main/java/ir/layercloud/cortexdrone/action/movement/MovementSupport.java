package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import net.minecraft.util.MathHelper;

/**
 * Pure calculation/start helpers for movement executors. No JSON here.
 */
final class MovementSupport {
    static final String ROUTE_AUTO = "auto";
    static final String ROUTE_DIRECT = "direct";
    static final String ROUTE_TUNNEL = "tunnel";
    static final String ROUTE_UP = "up";
    static final String ROUTE_DOWN = "down";

    private MovementSupport() {
    }

    static final class Target {
        final double x;
        final double y;
        final double z;

        Target(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static Target resolveTarget(MovementRequest request) {
        DroneController ctrl = request != null ? request.controller() : null;
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return null;
        if (request.hasStandoff()) {
            double[] st = FlightPathfinder.findMiningStandoff(ctrl.drone.field_70170_p, ctrl.drone,
                request.standoffX(), request.standoffY(), request.standoffZ());
            if (st == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(ctrl.drone.field_70170_p,
                    request.standoffX(), request.standoffY() + 1, request.standoffZ(), 3);
                if (open == null) return null;
                st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            return new Target(st[0], st[1], st[2]);
        }
        if (request.hasCoordinates()) {
            return new Target(request.x(), request.y(), request.z());
        }
        return null;
    }

    static String route(String requested, String fallback) {
        if (requested == null) return fallback;
        String route = requested.trim().toLowerCase();
        if (!ROUTE_AUTO.equals(route) && !ROUTE_DIRECT.equals(route)
            && !ROUTE_TUNNEL.equals(route) && !ROUTE_UP.equals(route)
            && !ROUTE_DOWN.equals(route)) {
            return fallback;
        }
        return route;
    }

    static Target surfaceTarget(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return null;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
        int top = ctrl.drone.field_70170_p.func_72976_f(cx, cz);
        return new Target(cx + 0.5, Math.min(250, top + 2.5), cz + 0.5);
    }

    static Target depthTarget(DroneController ctrl, int depth) {
        if (ctrl == null || ctrl.drone == null) return null;
        int x = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
        int y = Math.max(1, MathHelper.func_76128_c(ctrl.drone.field_70163_u) - Math.max(1, depth));
        int z = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
        return new Target(x + 0.5, y, z + 0.5);
    }

    static MovementResult start(AIDroneManager manager, MovementRequest request, Target target,
                                String route, String label) {
        DroneController ctrl = request != null ? request.controller() : null;
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
            return MovementResult.failure("No active drone");
        }
        double gx = target.x;
        double gy = target.y;
        double gz = target.z;
        ctrl.navigationGeneration++;
        ctrl.navigationRoute = route;
        ctrl.navViaSurface = false;
        ctrl.navFinalX = Double.NaN;
        ctrl.navFinalY = Double.NaN;
        ctrl.navFinalZ = Double.NaN;
        if (ROUTE_UP.equals(route)) {
            int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
            int cy = MathHelper.func_76128_c(ctrl.drone.field_70163_u);
            int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
            boolean sky = ctrl.drone.field_70170_p.func_72937_j(cx, cy, cz)
                || cy >= ctrl.drone.field_70170_p.func_72976_f(cx, cz) - 1;
            if (!sky) {
                ctrl.navFinalX = gx;
                ctrl.navFinalY = gy;
                ctrl.navFinalZ = gz;
                int top = ctrl.drone.field_70170_p.func_72976_f(cx, cz);
                gx = cx + 0.5;
                gy = Math.min(250, top + 2.5);
                gz = cz + 0.5;
                ctrl.navViaSurface = true;
            }
        }
        NavContext nav = NavContext.of(ctrl, gx, gy, gz, route);
        nav.setGoal(gx, gy, gz);
        manager.setDroneMode(ctrl, Mode.GOTO,
            label + " " + route + String.format(" (%.1f,%.1f,%.1f)", gx, gy, gz));
        return MovementResult.started(route, gx, gy, gz, ctrl.navViaSurface);
    }
}
