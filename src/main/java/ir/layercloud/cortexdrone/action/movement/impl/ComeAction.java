package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.action.movement.MovementTarget;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.service.movement.impl.MovementService;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** Single-use command: approach a point in front of a player. */
@RequiredArgsConstructor
public final class ComeAction extends MovementAction {
    private final MovementService service;
    private final EntityPlayerMP target;
    private final String label;

    @Override
    public void execute(MovementContext context) {
        DroneController controller = context.controller();
        World world = this.target.worldObj;
        double yaw = Math.toRadians(this.target.rotationYaw);
        double x = this.target.posX - Math.sin(yaw) * 2.0;
        double y = this.target.posY + 1.15;
        double z = this.target.posZ + Math.cos(yaw) * 2.0;

        if (!ItemClassifier.isPassableVoxel(world,
            MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z))) {
            int[] open = FlightPathfinder.findNearestPassableVoxel(world,
                MathHelper.floor_double(x), MathHelper.floor_double(y),
                MathHelper.floor_double(z), 2);
            if (open != null) {
                x = open[0] + 0.5;
                y = open[1] + 0.35;
                z = open[2] + 0.5;
            }
        }

        MovementResult result = this.service.startNavigation(controller,
            new MovementTarget(x, y, z), MovementRoute.AUTO, null, false, this.label);
        context.result(result);
        context.begin(result);
    }
}
