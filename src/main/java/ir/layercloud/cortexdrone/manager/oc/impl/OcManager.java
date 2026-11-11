package ir.layercloud.cortexdrone.manager.oc.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.oc.OcRequest;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.action.oc.impl.OcCallAction;
import ir.layercloud.cortexdrone.action.oc.impl.OcMachineAction;
import ir.layercloud.cortexdrone.context.impl.OcContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.service.oc.OcState;
import ir.layercloud.cortexdrone.service.oc.OcStateService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Owns the OpenComputers write surface: callback calls and machine controls.
 *
 * <p>Reads stay in {@link OcStateService}; this manager only runs actions. Every
 * operation is one-shot, so there is no retained execution to tick.</p>
 */
@SuppressWarnings("unused")
public final class OcManager extends ActionManager<OcContext, OcRequest, OcResult> {
    private final OcStateService state;

    public OcManager() {
        super("oc", "oc_call", "oc_machine");
        this.state = new OcStateService();
    }

    /**
     * Read-only snapshot for the {@code oc_state} and {@code oc_methods} tools.
     */
    public OcState inspect(DroneController controller) {
        return this.state.read(controller != null ? controller.drone : null);
    }

    /**
     * True when OpenComputers is installed and the drone fields resolved.
     */
    public boolean available() {
        return this.state.ready();
    }

    @Override
    protected OcContext createContext(DroneController controller, EntityPlayerMP player,
                                      String operation) {
        return new OcContext(controller, player, operation);
    }

    @Override
    protected Action<OcContext> createAction(OcContext context, String operation,
                                             OcRequest request) {
        if ("oc_call".equals(operation)) {
            return new OcCallAction(request, this.state);
        }
        return new OcMachineAction(request, this.state);
    }

    @Override
    protected OcResult result(OcContext context) {
        return context.result();
    }
}
