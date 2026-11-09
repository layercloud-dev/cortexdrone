package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Single-use command: spawn a particle burst.
 */
@RequiredArgsConstructor
public final class SpawnParticlesAction implements Action<VisualsContext> {
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        Drone drone = context.controller().drone;
        World world = drone.worldObj;
        double x = request.isCoordinates() ? request.getX() : drone.posX;
        double y = request.isCoordinates() ? request.getY() : drone.posY + DroneGeometry.AIM_OFFSET;
        double z = request.isCoordinates() ? request.getZ() : drone.posZ;
        if (world instanceof WorldServer) {
            ((WorldServer) world).func_147487_a(
                    request.getType(), x, y, z,
                    request.getCount(), request.getSpread(), request.getSpread(),
                    request.getSpread(), request.getSpeed());
        }
        context.result(VisualsResult.success(
                "spawned", request.getCount(),
                "type", request.getType()));
    }
}
