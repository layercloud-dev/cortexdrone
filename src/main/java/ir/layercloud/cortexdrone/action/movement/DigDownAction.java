package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

/** Dig straight down by depth, or to an explicit destination. */
@RequiredArgsConstructor
public class DigDownAction implements Action {
    private final AIDroneManager manager;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"digdown"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if (args == null) args = new JsonObject();
        MovementActionSupport.Target target = MovementActionSupport.resolveTarget(ctrl, args);
        if (target == null) {
            if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
                return MovementActionSupport.error("No active drone");
            }
            int depth = MovementActionSupport.intValue(args, "depth", 8);
            depth = Math.max(1, Math.min(64, depth));
            int x = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
            int y = Math.max(1, MathHelper.func_76128_c(ctrl.drone.field_70163_u) - depth);
            int z = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
            target = new MovementActionSupport.Target(x + 0.5, y, z + 0.5);
        }
        return MovementActionSupport.start(this.manager, ctrl, target,
            MovementActionSupport.ROUTE_DOWN, "DigDown");
    }
}
