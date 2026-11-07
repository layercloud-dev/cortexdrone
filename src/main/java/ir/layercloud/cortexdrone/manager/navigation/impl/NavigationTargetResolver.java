package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.manager.navigation.NavigationRequest;
import ir.layercloud.cortexdrone.manager.navigation.NavigationTarget;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Turns a {@link NavigationRequest} into a concrete point.
 *
 * <p>There are three cases, in order: an open standoff beside the block, a
 * block the drone must tunnel toward (no open face exists yet), or a plain
 * coordinate whose own voxel may be solid. Keeping this pure makes the
 * navigation manager's tick loop readable and testable.</p>
 */
final class NavigationTargetResolver {
    private NavigationTargetResolver() {
    }

    static NavigationTarget resolve(NavigationRequest request) {
        DroneController controller = request.getController();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        World world = controller.drone.worldObj;
        Drone drone = controller.drone;
        if (request.isStandoff()) {
            if (!ChunkAccess.ensureBlockLoaded(world,
                    request.getStandoffX(), request.getStandoffZ())) {
                return null;
            }
            double[] standoff = FlightPathfinder.findBlockStandoff(world, drone,
                    request.getStandoffX(), request.getStandoffY(), request.getStandoffZ());
            if (standoff != null) {
                return new NavigationTarget(standoff[0], standoff[1], standoff[2], false);
            }
            if (request.isAllowDig()) {
                // No open face yet. Aim at the block itself and let recovery
                // tunnel toward it, then stop as soon as it is within reach.
                return new NavigationTarget(request.getStandoffX() + 0.5,
                        request.getStandoffY() + 0.5, request.getStandoffZ() + 0.5, true);
            }
            int[] open = FlightPathfinder.findNearestPassableVoxel(world,
                    request.getStandoffX(), request.getStandoffY() + 1,
                    request.getStandoffZ(), 3);
            if (open == null) return null;
            return new NavigationTarget(open[0] + 0.5, open[1] + 0.35, open[2] + 0.5, false);
        }
        if (request.isCoordinates()) {
            int bx = MathHelper.floor_double(request.getX());
            int by = MathHelper.floor_double(request.getY());
            int bz = MathHelper.floor_double(request.getZ());
            if (!ItemClassifier.isPassableVoxel(world, bx, by, bz)) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(world, bx, by, bz, 3);
                if (open != null) {
                    return new NavigationTarget(open[0] + 0.5, open[1] + 0.65,
                            open[2] + 0.5, false);
                }
            }
            return new NavigationTarget(request.getX(), request.getY(), request.getZ(), false);
        }
        return null;
    }
}
