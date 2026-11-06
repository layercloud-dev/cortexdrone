package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import lombok.RequiredArgsConstructor;

/** Single-use command: hold position. */
@RequiredArgsConstructor
public final class StayAction extends MovementAction {
    private final MovementService service;
    private final String label;

    @Override
    public void execute(MovementContext context) {
        DroneController controller = context.controller();
        this.service.stop(controller, this.label);
        context.result(MovementResult.started("stay",
            controller.drone.posX, controller.drone.posY, controller.drone.posZ, false));
    }
}
