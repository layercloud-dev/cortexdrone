package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.action.Action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.ArrayList;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Handles 3D flight navigation, velocity nudges, follow/stay/come modes,
 * breadcrumb trail queries/backtracking, cave escape, and player airlift.
 */
@RequiredArgsConstructor
public class NavigationService implements Action {
    private final AIDroneManager manager;
    private final FlightPathfinder pathfinder;
    private final WorldScanner worldScanner;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() {
        return new String[]{"navigate", "move_to", "set_velocity", "follow", "stay", "stop", "come", "carry_player", "get_breadcrumbs", "retrace_breadcrumbs", "escape_cave"};
    }

    @Override
    public boolean supports(String operation) {
        for (String op : operations()) if (op.equals(operation)) return true;
        return false;
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if ("navigate".equals(m)) return navigate(ctrl, args);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.field_70170_p;

        if (m.equals("move_to")) {
            ctrl.goalX = args.get("x").getAsDouble();
            ctrl.goalY = args.get("y").getAsDouble();
            ctrl.goalZ = args.get("z").getAsDouble();
            this.manager.setDroneMode(ctrl, Mode.GOTO, String.format("RPC move_to (%.1f,%.1f,%.1f)", ctrl.goalX, ctrl.goalY, ctrl.goalZ));
            return res;
        }

        if (m.equals("set_velocity")) {
            double vx = args.has("vx") ? args.get("vx").getAsDouble() : (args.has("dx") ? args.get("dx").getAsDouble() : 0.0);
            double vy = args.has("vy") ? args.get("vy").getAsDouble() : (args.has("dy") ? args.get("dy").getAsDouble() : 0.0);
            double vz = args.has("vz") ? args.get("vz").getAsDouble() : (args.has("dz") ? args.get("dz").getAsDouble() : 0.0);
            d.field_70159_w = Math.max(-1.5, Math.min(1.5, vx));
            d.field_70181_x = Math.max(-1.5, Math.min(1.5, vy));
            d.field_70179_y = Math.max(-1.5, Math.min(1.5, vz));
            FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t + vx * 3.0, d.field_70163_u + vy * 3.0, d.field_70161_v + vz * 3.0, 0.2f);
            d.func_70091_d(d.field_70159_w, d.field_70181_x, d.field_70179_y);
            res.addProperty("x", d.field_70165_t);
            res.addProperty("y", d.field_70163_u);
            res.addProperty("z", d.field_70161_v);
            return res;
        }

        if (m.equals("follow")) {
            ctrl.targetPlayerName = args.has("target") ? args.get("target").getAsString() : (player != null ? player.func_70005_c_() : ctrl.ownerName);
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "RPC follow " + ctrl.targetPlayerName);
            return res;
        }

        if (m.equals("stay") || m.equals("stop")) {
            int callerLoopId = args.has("loopId") ? args.get("loopId").getAsInt() : 0;
            ctrl.cancelOtherLoops(callerLoopId);
            ctrl.clearAllTaskQueues();
            ctrl.goalX = d.field_70165_t;
            ctrl.goalY = d.field_70163_u;
            ctrl.goalZ = d.field_70161_v;
            this.manager.setDroneMode(ctrl, Mode.STAY, "RPC stay");
            return res;
        }

        if (m.equals("come")) {
            if (player != null) this.manager.commandComeToPlayer(player, ctrl);
            return res;
        }

        if (m.equals("carry_player")) {
            EntityPlayerMP p = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
            double gx, gy, gz;
            if (args.has("x") && args.has("z") && !args.get("x").isJsonNull()) {
                gx = args.get("x").getAsDouble();
                gz = args.get("z").getAsDouble();
                gy = args.has("y") && !args.get("y").isJsonNull()
                    ? args.get("y").getAsDouble()
                    : (world.func_72976_f(MathHelper.func_76128_c(gx), MathHelper.func_76128_c(gz)) + 1.0);
            } else {
                double[] base = this.worldScanner.findBaseLocation(world, p);
                gx = base[0];
                gy = base[1];
                gz = base[2];
            }
            if (p != null) {
                this.pathfinder.startCarryPlayerTask(ctrl, p, gx, gy, gz);
                res.addProperty("targetX", gx);
                res.addProperty("targetY", gy);
                res.addProperty("targetZ", gz);
                res.addProperty("queued", 1);
            } else {
                res.addProperty("ok", false);
                res.addProperty("error", "Player offline");
            }
            return res;
        }

        if (m.equals("get_breadcrumbs")) {
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

        if (m.equals("retrace_breadcrumbs")) {
            int steps = args.has("steps") ? args.get("steps").getAsInt() : 48;
            boolean toSurface = !args.has("toSurface") || args.get("toSurface").getAsBoolean();
            int queued = this.pathfinder.retraceBreadcrumbs(ctrl, steps, toSurface);
            res.addProperty("waypointsQueued", queued);
            return res;
        }

        if (m.equals("escape_cave")) {
            int steps = this.pathfinder.escapeCaveOrDigOut(ctrl, player);
            res.addProperty("steps", steps);
            return res;
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled navigation RPC method: " + m);
        return res;
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
        ctrl.goalX = gx; ctrl.goalY = gy; ctrl.goalZ = gz;
        this.manager.setDroneMode(ctrl, Mode.GOTO, "Navigate " + route + String.format(" (%.1f,%.1f,%.1f)", gx, gy, gz));
        JsonObject res = ok();
        res.addProperty("route", route);
        res.addProperty("viaSurface", ctrl.navViaSurface);
        res.add("goal", point(gx, gy, gz));
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
