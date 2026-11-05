package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import net.minecraft.util.MathHelper;

/**
 * Shared target/route plumbing for the split movement actions. This keeps each
 * action class small while preserving the master navigate contract.
 */
final class MovementActionSupport {
    static final String ROUTE_AUTO = "auto";
    static final String ROUTE_DIRECT = "direct";
    static final String ROUTE_TUNNEL = "tunnel";
    static final String ROUTE_UP = "up";
    static final String ROUTE_DOWN = "down";

    private MovementActionSupport() {
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

    static Target resolveTarget(DroneController ctrl, JsonObject args) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return null;
        JsonObject standoff = obj(args, "standoffOf");
        if (standoff == null) standoff = obj(args, "nearBlock");
        if (standoff != null) {
            int bx = intValue(standoff, "x", 0);
            int by = intValue(standoff, "y", 0);
            int bz = intValue(standoff, "z", 0);
            double[] st = FlightPathfinder.findMiningStandoff(ctrl.drone.field_70170_p, ctrl.drone, bx, by, bz);
            if (st == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(
                    ctrl.drone.field_70170_p, bx, by + 1, bz, 3);
                if (open == null) return null;
                st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            return new Target(st[0], st[1], st[2]);
        }
        if (!args.has("x") || !args.has("y") || !args.has("z")) return null;
        return new Target(num(args, "x", 0.0), num(args, "y", 0.0), num(args, "z", 0.0));
    }

    static String route(JsonObject args, String fallback) {
        String route = str(args, "route", fallback).trim().toLowerCase();
        if (!ROUTE_AUTO.equals(route) && !ROUTE_DIRECT.equals(route)
            && !ROUTE_TUNNEL.equals(route) && !ROUTE_UP.equals(route)
            && !ROUTE_DOWN.equals(route)) {
            return fallback;
        }
        return route;
    }

    static JsonObject start(AIDroneManager manager, DroneController ctrl, Target target,
                            String route, String label) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
            return error("No active drone");
        }
        double gx = target.x;
        double gy = target.y;
        double gz = target.z;
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
        JsonObject res = ok();
        res.addProperty("route", route);
        res.addProperty("viaSurface", ctrl.navViaSurface);
        res.add("goal", point(gx, gy, gz));
        return res;
    }

    static JsonObject ok() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        return r;
    }

    static JsonObject error(String msg) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", msg);
        return r;
    }

    static JsonObject obj(JsonObject a, String k) {
        return a != null && a.has(k) && !a.get(k).isJsonNull() && a.get(k).isJsonObject()
            ? a.getAsJsonObject(k) : null;
    }

    static String str(JsonObject a, String k, String f) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : f;
        } catch (Exception e) {
            return f;
        }
    }

    static double num(JsonObject a, String k, double f) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsDouble() : f;
        } catch (Exception e) {
            return f;
        }
    }

    static int intValue(JsonObject a, String k, int f) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : f;
        } catch (Exception e) {
            return f;
        }
    }

    static JsonObject point(double x, double y, double z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", Math.round(x * 100.0) / 100.0);
        p.addProperty("y", Math.round(y * 100.0) / 100.0);
        p.addProperty("z", Math.round(z * 100.0) / 100.0);
        return p;
    }
}
