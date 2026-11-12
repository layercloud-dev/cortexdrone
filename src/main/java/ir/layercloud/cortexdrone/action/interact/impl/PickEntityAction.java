package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
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
        // A drone carries one passenger. Calling mountEntity on a second entity
        // silently replaces the first, and the caller still gets picked:true for
        // both. Refuse instead of losing the passenger it is already carrying.
        if (drone.riddenByEntity != null && drone.riddenByEntity != target) {
            context.result(InteractionResult.failure(
                    "The drone is already carrying " + drone.riddenByEntity.getCommandSenderName()
                            + "; unpick that rider first.", "picked", false,
                    "ridingEntityId", drone.riddenByEntity.getEntityId(),
                    "ridingEntityName", drone.riddenByEntity.getCommandSenderName()));
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
        // Verification: the live log shows pick returning ok:true and the very
        // next unpick failing with "Entity is not riding this drone". A mount
        // can be refused by the entity (player dismount, teleport, plugin
        // guard), so success must be read back from the entity.
        if (target.ridingEntity != drone) {
            context.result(InteractionResult.failure(
                    "Entity " + target.getCommandSenderName()
                            + " did not stay mounted on the drone (the ride was refused).",
                    "picked", false,
                    "entityId", id,
                    "hint", "Move closer, retry once, or ask the player to right-click the drone; "
                            + "do not chain unpick after an unverified pick."));
            return;
        }
        context.result(InteractionResult.success(
                "picked", true,
                "entityId", id,
                "name", target.getCommandSenderName()));
    }
}
