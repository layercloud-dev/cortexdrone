package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

/** Go up through the surface, optionally continuing to a final destination. */
@RequiredArgsConstructor
public class DigUpAction implements Action {
    private final AIDroneManager manager;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"digup"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if (args == null) args = new JsonObject();
        MovementActionSupport.Target target = MovementActionSupport.resolveTarget(ctrl, args);
        if (target == null) {
            if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
                return MovementActionSupport.error("No active drone");
            }
            int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
            int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
            int top = ctrl.drone.field_70170_p.func_72976_f(cx, cz);
            target = new MovementActionSupport.Target(cx + 0.5, Math.min(250, top + 2.5), cz + 0.5);
        }
        return MovementActionSupport.start(this.manager, ctrl, target,
            MovementActionSupport.ROUTE_UP, "DigUp");
    }
}
