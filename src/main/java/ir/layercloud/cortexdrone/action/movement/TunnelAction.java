package ir.layercloud.cortexdrone.action.movement;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.model.DroneController;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/** Carve a tunnel to an explicit destination. */
@RequiredArgsConstructor
public class TunnelAction implements Action {
    private final AIDroneManager manager;

    @Override
    public String category() { return "movement"; }

    @Override
    public String[] operations() { return new String[]{"tunnel"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        if (args == null) args = new JsonObject();
        MovementActionSupport.Target target = MovementActionSupport.resolveTarget(ctrl, args);
        if (target == null) {
            return MovementActionSupport.error("tunnel requires x/y/z (or standoffOf:{x,y,z})");
        }
        return MovementActionSupport.start(this.manager, ctrl, target,
            MovementActionSupport.ROUTE_TUNNEL, "Tunnel");
    }
}
