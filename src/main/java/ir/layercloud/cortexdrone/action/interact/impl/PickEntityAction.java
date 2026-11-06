package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/**
 * Single-use interaction: mount one entity onto the drone.
 */
@RequiredArgsConstructor
public final class PickEntityAction implements Action<InteractionContext> {
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world", "picked", false));
            return;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        int id = request.getEntityId();
        Entity target = world.getEntityByID(id);
        if (target == null || target.isDead || target == drone) {
            context.result(InteractionResult.failure("No pickable entity with id " + id,
                    "picked", false));
            return;
        }
        if (target.ridingEntity == drone) {
            context.result(InteractionResult.success(
                    "picked", true,
                    "entityId", id,
                    "name", target.getCommandSenderName()));
            return;
        }

        double distance = drone.getDistance(target.posX, target.posY, target.posZ);
        if (distance > 4.0) {
            context.result(InteractionResult.movement(
                    new double[]{target.posX, target.posY + 0.5, target.posZ},
                    "needsMovement", true,
                    "action", "pick_entity",
                    "entityId", id,
                    "distance", Math.round(distance * 100.0) / 100.0));
            return;
        }

        context.targetEntity(id);
        target.mountEntity(drone);
        context.result(InteractionResult.success(
                "picked", true,
                "entityId", id,
                "name", target.getCommandSenderName()));
    }
}
