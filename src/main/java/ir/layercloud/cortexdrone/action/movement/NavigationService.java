package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

/**
 * Route selection and flight targets for the master navigate primitive, plus
 * the read-only breadcrumb trail used by inspect.
 */
@RequiredArgsConstructor
public class NavigationService implements Action {
    private final AIDroneManager manager;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"navigate", "get_breadcrumbs"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if ("navigate".equals(operation)) return navigate(ctrl, args);
        if ("get_breadcrumbs".equals(operation)) return getBreadcrumbs(ctrl, args);
        return error("Unsupported movement action: " + operation);
    }

    /** Master navigate primitive: choose a target/standoff and a route. */
    public JsonObject navigate(DroneController ctrl, JsonObject args) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return error("No active drone");
        if (args == null) args = new JsonObject();
        String route = str(args, "route", "auto").trim().toLowerCase();
        if (!route.equals("auto") && !route.equals("direct") && !route.equals("tunnel") && !route.equals("up")) route = "auto";
        double gx, gy, gz;
        JsonObject standoffOf = obj(args, "standoffOf");
        if (standoffOf == null) standoffOf = obj(args, "nearBlock");
        if (standoffOf != null) {
            int bx = intValue(standoffOf, "x", 0);
            int by = intValue(standoffOf, "y", 0);
            int bz = intValue(standoffOf, "z", 0);
            double[] st = FlightPathfinder.findMiningStandoff(ctrl.drone.field_70170_p, ctrl.drone, bx, by, bz);
            if (st == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(ctrl.drone.field_70170_p, bx, by + 1, bz, 3);
                if (open == null) return error("No reachable standoff near (" + bx + "," + by + "," + bz + ")");
                st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            gx = st[0]; gy = st[1]; gz = st[2];
        } else {
            if (!args.has("x") || !args.has("y") || !args.has("z")) return error("navigate requires x/y/z (or standoffOf:{x,y,z})");
            gx = num(args, "x", 0.0); gy = num(args, "y", 0.0); gz = num(args, "z", 0.0);
        }
        ctrl.navigationRoute = route;
        ctrl.navViaSurface = false;
        ctrl.navFinalX = Double.NaN; ctrl.navFinalY = Double.NaN; ctrl.navFinalZ = Double.NaN;
        if ("up".equals(route)) {
            int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
            int cy = MathHelper.func_76128_c(ctrl.drone.field_70163_u);
            int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
            boolean sky = ctrl.drone.field_70170_p.func_72937_j(cx, cy, cz)
                || cy >= ctrl.drone.field_70170_p.func_72976_f(cx, cz) - 1;
            if (!sky) {
                ctrl.navFinalX = gx; ctrl.navFinalY = gy; ctrl.navFinalZ = gz;
                int top = ctrl.drone.field_70170_p.func_72976_f(cx, cz);
                gx = cx + 0.5; gy = Math.min(250, top + 2.5); gz = cz + 0.5;
                ctrl.navViaSurface = true;
            }
        }
        NavContext nav = NavContext.of(ctrl, gx, gy, gz, route);
        nav.setGoal(gx, gy, gz);
        this.manager.setDroneMode(ctrl, Mode.GOTO, "Navigate " + route + String.format(" (%.1f,%.1f,%.1f)", gx, gy, gz));
        JsonObject res = ok();
        res.addProperty("route", route);
        res.addProperty("viaSurface", ctrl.navViaSurface);
        res.add("goal", point(gx, gy, gz));
        return res;
    }

    private JsonObject getBreadcrumbs(DroneController ctrl, JsonObject args) {
        if (ctrl == null) return error("No active drone");
        if (args == null) args = new JsonObject();
        int limit = args.has("limit") ? Math.min(256, Math.max(4, args.get("limit").getAsInt())) : 64;
        List<int[]> hist = new ArrayList<int[]>(ctrl.navigationHistory);
        JsonArray arr = new JsonArray();
        int start = Math.max(0, hist.size() - limit);
        for (int i = start; i < hist.size(); i++) {
            int[] pt = hist.get(i);
            JsonObject bo = new JsonObject();
            bo.addProperty("x", pt[0]);
            bo.addProperty("y", pt[1]);
            bo.addProperty("z", pt[2]);
            bo.addProperty("skyVisible", pt[3] == 1);
            arr.add(bo);
        }
        JsonObject res = ok();
        if (ctrl.lastSurfaceAnchor != null) {
            JsonObject sa = new JsonObject();
            sa.addProperty("x", ctrl.lastSurfaceAnchor[0]);
            sa.addProperty("y", ctrl.lastSurfaceAnchor[1]);
            sa.addProperty("z", ctrl.lastSurfaceAnchor[2]);
            res.add("lastSurfaceAnchor", sa);
        }
        res.addProperty("count", hist.size());
        res.add("breadcrumbs", arr);
        return res;
    }

    private static JsonObject ok() { JsonObject r = new JsonObject(); r.addProperty("ok", true); return r; }
    private static JsonObject error(String msg) { JsonObject r = new JsonObject(); r.addProperty("ok", false); r.addProperty("error", msg); return r; }
    private static JsonObject obj(JsonObject a, String k) { return a != null && a.has(k) && !a.get(k).isJsonNull() && a.get(k).isJsonObject() ? a.getAsJsonObject(k) : null; }
    private static String str(JsonObject a, String k, String f) { try { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : f; } catch (Exception e) { return f; } }
    private static double num(JsonObject a, String k, double f) { try { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsDouble() : f; } catch (Exception e) { return f; } }
    private static int intValue(JsonObject a, String k, int f) { try { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : f; } catch (Exception e) { return f; } }
    private static JsonObject point(double x, double y, double z) { JsonObject p = new JsonObject(); p.addProperty("x", Math.round(x * 100.0) / 100.0); p.addProperty("y", Math.round(y * 100.0) / 100.0); p.addProperty("z", Math.round(z * 100.0) / 100.0); return p; }
}
