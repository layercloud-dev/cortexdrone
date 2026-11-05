package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;

/** Read-only breadcrumb trail exposed through inspect. */
public class BreadcrumbAction implements Action {
    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"get_breadcrumbs"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if (ctrl == null) return MovementActionSupport.error("No active drone");
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
        JsonObject res = MovementActionSupport.ok();
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
}
