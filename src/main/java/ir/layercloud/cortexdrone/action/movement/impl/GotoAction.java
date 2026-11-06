package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.action.movement.MovementTarget;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import lombok.RequiredArgsConstructor;

/** Single-use command: navigate to explicit coordinates. */
@RequiredArgsConstructor
public final class GotoAction extends MovementAction {
    private final MovementService service;
    private final MovementTarget target;
    private final MovementRoute route;
    private final String label;

    @Override
    public void execute(MovementContext context) {
        MovementResult result = this.service.startNavigation(
            context.controller(), this.target, this.route, null, false, this.label);
        context.result(result);
        context.begin(result);
    }
}
