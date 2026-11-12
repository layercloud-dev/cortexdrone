package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.navigation.LandingSpot;
import ir.layercloud.cortexdrone.service.navigation.impl.LandingScanner;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Single-use interaction: dismount the drone's current rider.
 *
 * <p>Safety is enforced here, not only in the skills. A player (or any living
 * entity) is never released into a void, a lava pool, water, a tree canopy or
 * a one-block gap: the action scans the column below and refuses with the
 * nearest safe hover point. Two live deaths came from tools and generated
 * scripts calling {@code unpick_entity} at an arbitrary altitude.</p>
 *
 * <p>{@code force:true} is only for a caller who explicitly accepts the risk,
 * for example an admin request or a passenger who asked to be dropped.</p>
 */
@RequiredArgsConstructor
public final class UnpickEntityAction implements Action<InteractionContext> {
    /**
     * A rider below this altitude is not in free fall yet; 4 blocks is the damage line.
     */
    private static final int SEARCH_RADIUS = 8;

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
            // Unpicking is idempotent: if the entity is already off, the desired
            // state is reached. The old code returned a hard failure here, and
            // the live log shows a script aborting its whole carry loop after a
            // pick that failed silently. Report the fact instead of an error.
            context.result(InteractionResult.success(
                    "unpicked", true,
                    "alreadyDismounted", true,
                    "entityId", request.hasEntity() ? request.getEntityId() : -1,
                    "note", "the entity was not riding this drone; no action was needed"));
            return;
        }

        // Living riders must land on something solid. Items and boats are
        // exempt: dropping an item entity mid-air is harmless.
        if (rider instanceof net.minecraft.entity.EntityLivingBase) {
            InteractionResult refused = landingGuard(drone, world, rider);
            if (refused != null) {
                context.result(refused);
                return;
            }
        }

        int id = rider.getEntityId();
        String name = rider.getCommandSenderName();
        rider.mountEntity(null);
        context.result(InteractionResult.success(
                "unpicked", true,
                "entityId", id,
                "name", name));
    }

    /**
     * @return a {@code needsMovement} result pointing at the nearest safe hover
     * cell, or a plain failure when none exists; {@code null} when the current
     * position is safe
     */
    private InteractionResult landingGuard(Drone drone, World world, Entity rider) {
        double x = drone.posX;
        double y = drone.posY;
        double z = drone.posZ;
        // The current hover is safe when the rider would stand on a real floor
        // within a two-block drop.
        if (LandingScanner.canDismountHere(world, x, y, z)) return null;
        if (request.isForce()) {
            return null;
        }

        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);
        // Above ground a passenger can still be flown to open sky, so only an
        // open-sky spot is acceptable. Already underground, the cave floor is
        // the only landing there is; requiring sky there would strand them.
        boolean requireOpenSky = world.canBlockSeeTheSky(bx, by, bz)
                && world.canBlockSeeTheSky(bx, by + 1, bz);
        LandingSpot spot = LandingScanner.findDismountSpot(
                world, x, y, z, SEARCH_RADIUS, requireOpenSky);
        boolean isPlayer = rider instanceof EntityPlayer;
        if (spot == null) {
            int surface = world.getHeightValue(bx, bz);
            return InteractionResult.failure(
                    (isPlayer ? "Refusing to drop the player" : "Refusing to drop the rider")
                            + ": no safe landing column within " + SEARCH_RADIUS + " blocks. "
                            + "The drone is at y=" + by + " and the surface here is y=" + surface + ".",
                    "unpicked", false,
                    "unsafeLanding", true,
                    "needSafeLanding", true,
                    "droneY", by,
                    "surfaceY", surface,
                    "hint", "Fly to open ground first, or call find_safe_landing and goto its "
                            + "spot, then unpick. Do not drop a rider mid-air.");
        }
        double hx = spot.getX() + DroneGeometry.CELL_CENTER;
        double hy = spot.getY() + DroneGeometry.AIM_OFFSET;
        double hz = spot.getZ() + DroneGeometry.CELL_CENTER;
        return InteractionResult.movement(
                new double[]{hx, hy, hz},
                "needsMovement", true,
                "unpicked", false,
                "unsafeLanding", true,
                "needSafeLanding", true,
                "safeX", spot.getX(),
                "safeY", spot.getY(),
                "safeZ", spot.getZ(),
                "safeFloor", spot.getFloorBlock(),
                "hint", "Move to (safeX, safeY, safeZ) before releasing the rider. "
                        + "Dropping them here would hurt or suffocate them.");
    }
}
