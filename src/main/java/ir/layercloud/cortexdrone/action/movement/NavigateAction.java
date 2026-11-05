package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * General flight navigation. Route defaults to auto and may be auto/direct.
 * Tunnel/up/down have their own action classes but remain accepted here for
 * backward compatibility with {@code drone.moveTo({route:...})}.
 */
@RequiredArgsConstructor
public class NavigateAction implements Action {
    private final AIDroneManager manager;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"navigate"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if (args == null) args = new JsonObject();
        String route = MovementActionSupport.route(args, MovementActionSupport.ROUTE_AUTO);
        MovementActionSupport.Target target = MovementActionSupport.resolveTarget(ctrl, args);
        if (target == null) {
            return MovementActionSupport.error("navigate requires x/y/z (or standoffOf:{x,y,z})");
        }
        return MovementActionSupport.start(this.manager, ctrl, target, route, "Navigate");
    }
}
