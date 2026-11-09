package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.service.world.WorldBounds;
import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Clears leaf blocks that physically hold a stalled drone. Leaves have no
 * collision ray worth planning around and the drone cannot dig them itself
 * while it is inside them, so the safety step removes the ones in its own
 * 3x3x3 shell.
 */
@RequiredArgsConstructor
public final class ClearLeafObstructionAction implements Action<NavigationContext> {
    @Override
    public void execute(NavigationContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null) {
            context.result(NavigateResult.failure("No active drone"));
            context.complete();
            return;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (world == null) {
            context.complete();
            return;
        }
        int bx = MathHelper.floor_double(drone.posX);
        int by = MathHelper.floor_double(drone.posY);
        int bz = MathHelper.floor_double(drone.posZ);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    if (!WorldBounds.inWorld(y) || world.isAirBlock(x, y, z)) continue;
                    Block block = world.getBlock(x, y, z);
                    if (ItemClassifier.isLeafBlock(block)) {
                        world.setBlockToAir(x, y, z);
                    }
                }
            }
        }
        context.result(NavigateResult.done());
        context.complete();
    }
}
