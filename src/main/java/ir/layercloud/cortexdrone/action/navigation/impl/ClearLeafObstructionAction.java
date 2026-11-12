package ir.layercloud.cortexdrone.action.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.context.impl.NavigationContext;
import ir.layercloud.cortexdrone.service.world.BlockPlacer;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
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
    /**
     * Upper bound on leaves removed in one stall pass.
     */
    private static final int MAX_CLEARED = 64;

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
        // Canopies are wider than the 3x3x3 body shell: a drone stalled in a
        // forest cleared one leaf every ~20 s and spent 13 minutes in the same
        // tree (live log 02:05-02:17). Clear the whole 5x3x5 canopy pocket in
        // one pass, but only leaves/plants and only up to a small cap.
        int cleared = 0;
        for (int dx = -2; dx <= 2 && cleared < MAX_CLEARED; dx++) {
            for (int dy = -1; dy <= 2 && cleared < MAX_CLEARED; dy++) {
                for (int dz = -2; dz <= 2 && cleared < MAX_CLEARED; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    if (!WorldBounds.inWorld(y) || world.isAirBlock(x, y, z)) continue;
                    Block block = world.getBlock(x, y, z);
                    if (block != null && ItemClassifier.isLeafBlock(block)) {
                        world.setBlockToAir(x, y, z);
                        // Leaves in a partially loaded chunk need an explicit
                        // block update or the client keeps showing the canopy.
                        BlockPlacer.refresh(world, x, y, z, Blocks.air);
                        cleared++;
                    }
                }
            }
        }
        context.result(NavigateResult.done());
        context.complete();
    }
}
