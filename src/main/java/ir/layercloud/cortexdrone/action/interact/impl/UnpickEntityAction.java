package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.model.DroneController;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/**
 * Single-use interaction: dismount the drone's current rider.
 */
@RequiredArgsConstructor
public final class UnpickEntityAction implements Action<InteractionContext> {
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world", "unpicked", false));
            return;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        Entity rider = request.hasEntity() ? world.getEntityByID(request.getEntityId())
                : drone.riddenByEntity;
        if (rider == null || rider.ridingEntity != drone) {
            context.result(InteractionResult.failure(
                    "Entity is not riding this drone", "unpicked", false));
            return;
        }

        int id = rider.getEntityId();
        String name = rider.getCommandSenderName();
        rider.mountEntity(null);
        context.result(InteractionResult.success(
                "unpicked", true,
                "entityId", id,
                "name", name));
    }
}
