package ir.layercloud.cortexdrone.context.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Execution state for one OpenComputers operation.
 *
 * <p>OC calls complete inside the action; the context only carries the typed
 * result out to the manager.</p>
 */
public final class OcContext extends ActionContextBase {
    private OcResult result = OcResult.failure("OpenComputers operation did not run.");

    public OcContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public OcResult result() {
        return this.result;
    }

    public void result(OcResult value) {
        this.result = value != null ? value : OcResult.failure("OpenComputers operation returned no result.");
    }
}
