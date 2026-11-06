package ir.layercloud.cortexdrone.action.effect.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import ir.layercloud.cortexdrone.context.action.effect.impl.EffectContext;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/** Single-use command: spawn a particle burst. */
@RequiredArgsConstructor
public final class SpawnParticlesAction implements Action<EffectContext> {
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
        Drone drone = context.controller().drone;
        World world = drone.worldObj;
        double x = request.isCoordinates() ? request.getX() : drone.posX;
        double y = request.isCoordinates() ? request.getY() : drone.posY + 0.35;
        double z = request.isCoordinates() ? request.getZ() : drone.posZ;
        if (world instanceof WorldServer) {
            ((WorldServer) world).func_147487_a(
                request.getType(), x, y, z,
                request.getCount(), request.getSpread(), request.getSpread(),
                request.getSpread(), request.getSpeed());
        }
        context.result(EffectResult.success(
            "spawned", request.getCount(),
            "type", request.getType()));
    }
}
