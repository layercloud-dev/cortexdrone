package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import lombok.RequiredArgsConstructor;

/** Single-use command: follow a player. */
@RequiredArgsConstructor
public final class FollowAction extends MovementAction {
    private final MovementService service;
    private final String targetName;
    private final String label;

    @Override
    public void execute(MovementContext context) {
        DroneController controller = context.controller();
        this.service.follow(controller, this.targetName, this.label);
        context.result(MovementResult.started("follow",
            controller.drone.posX, controller.drone.posY, controller.drone.posZ, false));
    }
}
