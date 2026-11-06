package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;

/**
 * Shared movement lifecycle. Each movement action owns its own target/route
 * decision; this base only observes whether navigation is still active.
 */
public abstract class MovementAction implements Action<MovementContext> {
    @Override
    public void tick(MovementContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            context.complete();
            return;
        }

        double x = controller.drone.posX;
        double y = controller.drone.posY;
        double z = controller.drone.posZ;
        double dx = x - context.goalX();
        double dy = y - context.goalY();
        double dz = z - context.goalZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        context.recordProgress(x, y, z, distance);

        if (controller.mode != Mode.GOTO || controller.navigationGeneration != context.generation()) {
            context.finish(controller.mode != Mode.GOTO && distance <= 1.25);
        }
    }
}
