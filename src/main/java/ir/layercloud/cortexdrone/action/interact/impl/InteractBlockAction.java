package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.InteractionSupport;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/** Single-use command: activate exactly one block. */
@RequiredArgsConstructor
public final class InteractBlockAction implements Action<InteractionContext> {
    private final InventoryService inventoryService;
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        EntityPlayerMP player = context.player();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world", "activated", false));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        context.targetBlock(x, y, z);

        Block block = world.getBlock(x, y, z);
        if (ItemClassifier.isLiquidBlock(block) && world.getBlockMetadata(x, y, z) == 0) {
            boolean scooped = this.inventoryService.scoopLiquidAt(world, controller, player, x, y, z);
            context.result(InteractionResult.success(
                "activated", scooped,
                "scooped", scooped));
            return;
        }

        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null) {
            context.result(InteractionResult.failure("No fake player on drone", "activated", false));
            return;
        }
        InteractionSupport.syncFakePlayerToDrone(fakePlayer, drone);

        boolean activated = block != null && block.onBlockActivated(
            world, x, y, z, fakePlayer, request.getSide(), 0.5f, 0.5f, 0.5f);
        context.result(InteractionResult.success(
            "activated", activated,
            "block", ItemClassifier.getBlockDisplayName(
                world.getBlock(x, y, z), world.getBlockMetadata(x, y, z))));
    }
}
