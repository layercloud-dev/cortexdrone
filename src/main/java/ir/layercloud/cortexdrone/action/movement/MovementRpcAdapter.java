package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * RPC boundary for movement. This is the only movement class that knows about
 * JsonObject: it parses arguments, calls typed executors and serializes their
 * MovementResult.
 */
public class MovementRpcAdapter implements Action {
    private final MovementActionRegistry actions = new MovementActionRegistry();

    public MovementRpcAdapter(AIDroneManager manager) {
        this.actions.register(new NavigateAction(manager));
        this.actions.register(new TunnelAction(manager));
        this.actions.register(new DigUpAction(manager));
        this.actions.register(new DigDownAction(manager));
        this.actions.register(new BreadcrumbAction());
    }

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() {
        return new String[]{"navigate", "tunnel", "digup", "digdown", "get_breadcrumbs"};
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        MovementRequest request = parse(ctrl, player, args);
        return toJson(this.actions.execute(operation, request));
    }

    private static MovementRequest parse(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        JsonObject safe = args != null ? args : new JsonObject();
        boolean hasCoordinates = safe.has("x") && safe.has("y") && safe.has("z")
            && !safe.get("x").isJsonNull() && !safe.get("y").isJsonNull() && !safe.get("z").isJsonNull();
        double x = hasCoordinates ? safe.get("x").getAsDouble() : 0.0;
        double y = hasCoordinates ? safe.get("y").getAsDouble() : 0.0;
        double z = hasCoordinates ? safe.get("z").getAsDouble() : 0.0;

        JsonObject standoff = obj(safe, "standoffOf");
        if (standoff == null) standoff = obj(safe, "nearBlock");
        boolean hasStandoff = standoff != null
            && standoff.has("x") && standoff.has("y") && standoff.has("z");
        int sx = hasStandoff ? standoff.get("x").getAsInt() : 0;
        int sy = hasStandoff ? standoff.get("y").getAsInt() : 0;
        int sz = hasStandoff ? standoff.get("z").getAsInt() : 0;

        String route = safe.has("route") && !safe.get("route").isJsonNull()
            ? safe.get("route").getAsString() : null;
        int depth = safe.has("depth") && !safe.get("depth").isJsonNull()
            ? safe.get("depth").getAsInt() : 8;
        int limit = safe.has("limit") && !safe.get("limit").isJsonNull()
            ? safe.get("limit").getAsInt() : 64;
        return new MovementRequest(ctrl, player, hasCoordinates, x, y, z,
            hasStandoff, sx, sy, sz, route, depth, limit);
    }

    private static JsonObject toJson(MovementResult result) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", result.isOk());
        if (!result.isOk()) {
            res.addProperty("error", result.error() != null ? result.error() : "Movement failed");
            return res;
        }
        if (result.hasBreadcrumbs()) {
            JsonArray arr = new JsonArray();
            List<int[]> page = result.breadcrumbs();
            for (int[] pt : page) {
                JsonObject bo = new JsonObject();
                bo.addProperty("x", pt[0]);
                bo.addProperty("y", pt[1]);
                bo.addProperty("z", pt[2]);
                bo.addProperty("skyVisible", pt[3] == 1);
                arr.add(bo);
            }
            if (result.lastSurfaceAnchor() != null) {
                JsonObject sa = new JsonObject();
                sa.addProperty("x", result.lastSurfaceAnchor()[0]);
                sa.addProperty("y", result.lastSurfaceAnchor()[1]);
                sa.addProperty("z", result.lastSurfaceAnchor()[2]);
                res.add("lastSurfaceAnchor", sa);
            }
            res.addProperty("count", result.breadcrumbCount());
            res.add("breadcrumbs", arr);
            return res;
        }
        res.addProperty("route", result.route());
        res.addProperty("viaSurface", result.viaSurface());
        JsonObject goal = new JsonObject();
        goal.addProperty("x", Math.round(result.goalX() * 100.0) / 100.0);
        goal.addProperty("y", Math.round(result.goalY() * 100.0) / 100.0);
        goal.addProperty("z", Math.round(result.goalZ() * 100.0) / 100.0);
        res.add("goal", goal);
        return res;
    }

    private static JsonObject obj(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).isJsonObject()
            ? a.getAsJsonObject(k) : null;
    }
}
