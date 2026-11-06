package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import lombok.RequiredArgsConstructor;

/** Single-use command: start carrying a player to a destination. */
@RequiredArgsConstructor
public final class CarryAction extends MovementAction {
    private final FlightPathfinder pathfinder;
    private final double x;
    private final double y;
    private final double z;
    private final String label;

    @Override
    public void execute(MovementContext context) {
        this.pathfinder.startCarryPlayerTask(
            context.controller(), context.player(), this.x, this.y, this.z);
        context.result(MovementResult.started("carry", this.x, this.y, this.z, false));
    }
}
